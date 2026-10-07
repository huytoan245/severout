import test from 'node:test';
import assert from 'node:assert/strict';
import {harness,installation,tokenPayload,wakePayload} from './enrollment-fixture.mjs';
import {bindingHash,constantTimeEqual} from '../src/device-binding.js';
import {b64} from '../src/google.js';
const payload=()=>({familyId:'family-01',deviceId:'child-01',version:'2.3.2'});
const rebind=async(h,app)=>h.call(app,app.role==='parent'?'rebindParent':'rebindChild',await h.proof(app,'rebind',payload()));
test('retained Firebase UID with new key must refresh identity so old Firestore JWT loses authority',async()=>{
  const h=harness(),old=await installation('retained','parent');await h.enroll(old);
  const retained=await installation(old.uid,'parent',old.material);assert.equal((await h.proof(retained,'rebind',payload())).body.error,'fresh_identity_required');
  const next=await installation('fresh-anonymous','parent',old.material);assert.equal((await rebind(h,next)).status,200);
  const f=await h.api.readFamily();assert.equal(f.epoch,2);assert.equal(f.parentKey,next.publicKey);assert.equal((await h.call(next,'state')).status,200);assert.equal((await h.call(old,'state')).status,403);
  assert.equal((await h.enroll(old)).status,403);
});
test('old pending wake/token nonce epoch cannot be accepted after another role rebind',async()=>{
  const h=harness(),p=await installation('p','parent'),c=await installation('c','child');await h.enroll(p);await h.enroll(c);
  const wake=await h.proof(p,'wake',wakePayload()),token=await h.proof(c,'token',tokenPayload('token-before-epoch',1));
  const next=await installation('p-new','parent',p.material);assert.equal((await rebind(h,next)).status,200);
  assert.equal((await h.call(p,'wake',wake)).status,403);assert.equal((await h.call(c,'token',token)).body.error,'family_changed');
});
test('REBIND nonce cannot substitute REGISTER or another role proof',async()=>{
  const h=harness(),old=await installation('old','parent');await h.enroll(old);const next=await installation('new','parent',old.material);
  const p=await h.proof(next,'rebind',payload());assert.equal((await h.call(next,'registerParent',p)).body.error,'invalid_nonce');assert.equal((await h.call(next,'rebindChild',p)).body.error,'invalid_nonce');
});
test('unsafe generation overflow fails closed without state write',async()=>{
  const h=harness(),old=await installation('old','parent');await h.enroll(old);const f=await h.api.readFamily();await h.api.writeFamily(f,{...f,parentRebindGeneration:Number.MAX_SAFE_INTEGER});
  const next=await installation('new','parent',old.material);assert.equal((await rebind(h,next)).status,403);assert.equal((await h.api.readFamily()).parentUid,'old');
});
for(const role of ['parent','child']) {
  test(`A/B permanent initial ${role} claim after one year atomically stores binding and consumes`,async()=>{
    const h=harness(),app=await installation('old-'+role,role);h.advance(366*86400000);
    assert.equal((await h.enroll(app)).status,200);const f=await h.api.readFamily();
    assert.equal(f[role+'BootstrapConsumed'],true);assert.equal(f[role+'DeviceBindingHash'],await bindingHash(h.env.DEVICE_BINDING_PEPPER,role,app.material));
    assert.equal(f[role+'RebindGeneration'],0);assert.ok(!JSON.stringify([...h.documents]).includes(app.material));
  });
  test(`F/G/T/X ${role} reinstall with new UID/key and same material survives restart; retained update does not rebind`,async()=>{
    const h=harness(),old=await installation('old-'+role,role);await h.enroll(old);
    h.restart();const fresh=await installation('new-'+role,role,old.material);
    assert.equal((await rebind(h,fresh)).status,200);h.restart();
    const before=await h.api.readFamily();assert.equal(before[role+'Uid'],fresh.uid);assert.equal(before[role+'Key'],fresh.publicKey);assert.equal(before.epoch,2);assert.equal(before[role+'RebindGeneration'],1);
    assert.equal((await h.enroll(fresh)).status,200);assert.equal((await h.api.readFamily()).epoch,2);
    assert.equal((await h.call(old,'state')).status,403);assert.equal((await h.enroll(old)).body.error,'retired_identity');
    assert.equal((await h.proof(old,role==='parent'?'wake':'token',role==='parent'?wakePayload():tokenPayload('old-token-00000',1))).status,403);
    const oldKey={...old,uid:fresh.uid};assert.equal((await h.enroll(oldKey)).status,403);
  });
  test(`K/L/M/N/O ${role} wrong material or cross-role material cannot take slot`,async()=>{
    const h=harness(),old=await installation('old-'+role,role);await h.enroll(old);
    const other=await installation('other',role==='parent'?'child':'parent');
    for(const material of [other.material,b64(crypto.getRandomValues(new Uint8Array(32)))]) {
      const stranger=await installation('different-'+material.slice(0,4),role,material);
      assert.equal((await rebind(h,stranger)).body.error,'operator_recovery_required');
    }
    assert.equal((await h.api.readFamily())[role+'Uid'],old.uid);
    const fresh=await installation('candidate',role,old.material);
    assert.equal((await h.call(fresh,'challenge',{familyId:'other',deviceId:'child-01',role,purpose:'rebind'})).status,400);
    assert.equal((await h.call(fresh,'challenge',{familyId:'family-01',deviceId:'other',role,purpose:'rebind'})).status,400);
    const wrong=await h.proof(fresh,'rebind',{...payload(),deviceId:'other'});
    assert.equal((await h.call(fresh,role==='parent'?'rebindParent':'rebindChild',wrong)).status,400);
  });
  test(`Q ${role} independent competing rebind CAS yields one transition`,async()=>{
    const h=harness(),old=await installation('old-'+role,role);await h.enroll(old);
    const a=await installation('a',role,old.material),b=await installation('b',role,old.material);
    const pa=await h.proof(a,'rebind',payload()),pb=await h.proof(b,'rebind',payload()),other=h.otherInstance();
    const result=await Promise.all([h.call(a,role==='parent'?'rebindParent':'rebindChild',pa),other(b,role==='parent'?'rebindParent':'rebindChild',pb)]);
    assert.equal(result.filter(r=>r.status===200).length,1);assert.ok([403,409].includes(result.find(r=>r.status!==200).status));
    const f=await h.api.readFamily();assert.equal(f.epoch,2);assert.equal(f[role+'RebindGeneration'],1);
  });
  test(`R/S/P ${role} response lost after atomic commit retries same durable proof exactly once`,async()=>{
    const h=harness(),old=await installation('old-'+role,role);await h.enroll(old);
    const fresh=await installation('new-'+role,role,old.material),p=await h.proof(fresh,'rebind',payload());
    h.crashAfterCommit();assert.equal((await h.call(fresh,role==='parent'?'rebindParent':'rebindChild',p)).status,503);
    h.restart();assert.equal((await h.call(fresh,role==='parent'?'rebindParent':'rebindChild',p)).status,200);
    assert.equal((await h.api.readFamily()).epoch,2);assert.equal((await h.call(fresh,role==='parent'?'rebindParent':'rebindChild',{...p,signature:'a'.repeat(86)})).status,409);
    h.advance(121000);assert.equal((await h.call(fresh,role==='parent'?'rebindParent':'rebindChild',p)).body.error,'invalid_nonce');
    assert.equal((await h.enroll(fresh)).status,200);assert.equal((await h.api.readFamily()).epoch,2);
  });
}
test('J/W child rebind clears token authority atomically and preserves 5001 journey events and coordinates',async()=>{
  const h=harness(),old=await installation('old-child','child');await h.enroll(old);
  h.documents.set('devices/child-01',{updateTime:'seed',fields:{lastLat:{integerValue:'20'},fcmToken:{stringValue:'old-token-0000'},fcmTokenOwnerUid:{stringValue:old.uid},fcmTokenGeneration:{integerValue:'100'}}});
  for(let n=0;n<5001;n++)h.documents.set('devices/child-01/events/'+n,{updateTime:'unchanged',fields:{time:{integerValue:String(n)},lat:{integerValue:'20'}}});
  const fresh=await installation('new-child','child',old.material),p=await h.proof(fresh,'rebind',payload());
  h.failNextWrite();assert.equal((await h.call(fresh,'rebindChild',p)).status,503);
  assert.equal((await h.api.readFamily()).childUid,old.uid);assert.equal((await h.api.readDocument(h.api.docUrl())).fcmTokenOwnerUid,old.uid);
  assert.equal((await h.call(fresh,'rebindChild',p)).status,200);
  const d=await h.api.readDocument(h.api.docUrl());assert.equal(d.fcmToken,'');assert.equal(d.fcmTokenOwnerUid,'');assert.equal(d.fcmTokenGeneration,101);assert.equal(d.lastLat,20);
  assert.equal([...h.documents.keys()].filter(k=>k.includes('/events/')).length,5001);
  for(let n=0;n<5001;n++)assert.equal(h.documents.get('devices/child-01/events/'+n).updateTime,'unchanged');
  assert.equal((await h.call(fresh,'token',await h.proof(fresh,'token',tokenPayload('new-token-0000',102)))).status,200);
});
test('D/E unclaimed revoked and v231 schema/capability cannot enroll',async()=>{
  const h=harness(),app=await installation('app','parent'),fields=h.documents.get('families/family-01').fields;
  fields.parentBootstrapRevoked={booleanValue:true};assert.equal((await h.enroll(app)).body.error,'bootstrap_revoked');
  fields.parentBootstrapRevoked={booleanValue:false};fields.schemaVersion={integerValue:'231'};
  assert.equal((await h.enroll(app)).body.error,'invalid_family_state');
});
test('missing/lost/changed pepper fails closed for rebind, never falls back to bootstrap',async()=>{
  const h=harness(),old=await installation('old','parent');await h.enroll(old);const fresh=await installation('new','parent',old.material);
  delete h.env.DEVICE_BINDING_PEPPER;assert.equal((await h.proof(fresh,'rebind',payload())).body.error,'binding_not_configured');
  h.env.DEVICE_BINDING_PEPPER=b64(crypto.getRandomValues(new Uint8Array(32)));assert.equal((await rebind(h,fresh)).body.error,'operator_recovery_required');
  assert.equal((await h.api.readFamily()).parentUid,'old');
});
test('binding domain separation and constant-time equality reject malformed values',async()=>{
  const pepper=b64(crypto.getRandomValues(new Uint8Array(32))),material=await import('../src/enrollment.js').then(m=>m.digest('fixture'));
  const p=await bindingHash(pepper,'parent',material),c=await bindingHash(pepper,'child',material);
  assert.ok(constantTimeEqual(p,p));assert.ok(!constantTimeEqual(p,c));assert.ok(!constantTimeEqual(p,'malformed'));
});
