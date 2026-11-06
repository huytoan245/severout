import test from 'node:test';
import assert from 'node:assert/strict';
import {harness,installation} from './enrollment-fixture.mjs';
import {digest,proofMessage} from '../src/enrollment.js';
import {b64} from '../src/google.js';
import {recoveryGuard} from '../src/recovery-guard.js';
async function setup(){
  const h=harness(),old=await installation('old-parent','parent'),child=await installation('child','child');
  await h.enroll(old);await h.enroll(child);
  const f=h.documents.get('families/family-01');f.updateTime='2026-11-06T08:00:00.123456Z';
  const fresh=await installation('new-parent','parent',old.material);
  const guard={revision:f.updateTime,epoch:1,generation:0,operationId:crypto.randomUUID()};
  return {h,old,child,fresh,guard};
}
async function proof(x,who=x.fresh,guard=x.guard){
  const c=await x.h.call(who,'challenge',{familyId:'family-01',deviceId:'child-01',role:who.role,purpose:'rebind'},guard);
  if(c.status!==200)return c;
  assert.equal(c.body.recoveryGuardHash,await digest(JSON.stringify(guard)));
  const raw=JSON.stringify({familyId:'family-01',deviceId:'child-01',version:'2.3.2',deviceRecoveryMaterial:who.material});
  const signature=b64(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},who.pair.privateKey,new TextEncoder().encode(proofMessage(who.uid,who.role,c.body.nonce,'rebind',await digest(raw)))));
  return {nonce:c.body.nonce,signature,publicKey:who.publicKey,payload:raw};
}
test('guarded Parent rebind preserves Child/token/history and retires old Parent atomically',async()=>{
  const x=await setup();const before=structuredClone(x.h.documents.get('families/family-01').fields);
  x.h.documents.set('devices/child-01',{updateTime:'device',fields:{fcmToken:{stringValue:'fixture-token'},lastSeen:{integerValue:'123'}}});
  x.h.documents.set('devices/child-01/events/preserved',{fields:{time:{integerValue:'123'}}});
  const device=structuredClone(x.h.documents.get('devices/child-01'));
  assert.equal((await x.h.call(x.fresh,'rebindParent',await proof(x),x.guard)).status,200);
  const f=await x.h.api.readFamily();assert.equal(f.epoch,2);assert.equal(f.parentRebindGeneration,1);
  assert.equal(f.parentUid,x.fresh.uid);assert.equal(f.childUid,before.childUid.stringValue);assert.equal(f.childKey,before.childKey.stringValue);
  assert.deepEqual(x.h.documents.get('devices/child-01'),device);assert.ok(x.h.documents.has('devices/child-01/events/preserved'));
  assert.ok(f.retiredUidHashes.includes(await digest(x.old.uid)));assert.ok(f.retiredKeyHashes.includes(await digest(x.old.publicKey)));
  assert.equal((await x.h.call(x.old,'state')).status,403);
});
for(const field of ['revision','epoch','generation'])test(`approved ${field} drift stops before challenge`,async()=>{
  const x=await setup();x.guard[field]=field==='revision'?'2026-11-06T08:00:00.123457Z':99;
  assert.equal((await proof(x)).body.error,'recovery_snapshot_changed');assert.equal((await x.h.api.readFamily()).parentUid,x.old.uid);
});
test('revision change with same epoch between challenge and commit stops',async()=>{
  const x=await setup(),p=await proof(x);x.h.documents.get('families/family-01').updateTime='2026-11-06T08:00:00.999999Z';
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard)).body.error,'recovery_snapshot_changed');
  assert.equal((await x.h.api.readFamily()).parentUid,x.old.uid);
});
test('guard cannot be removed/changed after nonce issuance or used for Child',async()=>{
  const x=await setup(),p=await proof(x);
  assert.equal((await x.h.call(x.fresh,'rebindParent',p)).body.error,'recovery_guard_changed');
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,{...x.guard,operationId:crypto.randomUUID()})).body.error,'recovery_guard_changed');
  const child=await installation('new-child','child',x.child.material);
  assert.equal((await x.h.call(child,'challenge',{familyId:'family-01',deviceId:'child-01',role:'child',purpose:'rebind'},x.guard)).status,400);
});
test('wrong same-device material still fails despite valid guard/new UID/new key',async()=>{
  const x=await setup(),stranger=await installation('new-stranger','parent');
  assert.equal((await x.h.call(stranger,'rebindParent',await proof(x,stranger),x.guard)).body.error,'operator_recovery_required');
});
test('lost commit response and process restart replay the exact proof once',async()=>{
  const x=await setup(),p=await proof(x);x.h.crashAfterCommit();
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard)).status,503);x.h.restart();
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard)).status,200);
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard)).status,200);
  assert.equal((await x.h.api.readFamily()).epoch,2);assert.equal([...x.h.documents.keys()].filter(k=>k.startsWith('familyRebind/')).length,1);
});
test('CAS race after final family read rejects the atomic family/audit commit',async()=>{
  const x=await setup(),p=await proof(x),commit=x.h.api.rebindIdentity.bind(x.h.api);
  x.h.api.rebindIdentity=async(...args)=>{x.h.documents.get('families/family-01').updateTime='raced';return commit(...args);};
  assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard)).body.error,'rebind_raced');
  assert.equal((await x.h.api.readFamily()).parentUid,x.old.uid);assert.equal([...x.h.documents.keys()].filter(k=>k.startsWith('familyRebind/')).length,0);
});
test('invalid guard shape and unsafe counters fail closed',()=>{
  for(const value of [{},{revision:'bad',epoch:1,generation:0,operationId:crypto.randomUUID()},{revision:'2026-11-06T08:00:00.123456Z',epoch:1,generation:Infinity,operationId:crypto.randomUUID()}])assert.throws(()=>recoveryGuard(value));
});
