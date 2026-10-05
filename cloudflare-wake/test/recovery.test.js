import test from 'node:test';
import { randomBytes,createHash } from 'node:crypto';
import { bootstrapPlan } from '../bootstrap-plan.mjs';
const sha=()=>createHash('sha256').update(randomBytes(32)).digest('base64url');
const fresh=()=>({familyId:'family-01',deviceId:'child-01',versionName:'2.3.1',versionCode:35,roles:{parent:{sha256:sha(),expiresAt:604800000},child:{sha256:sha(),expiresAt:604800000}}});
import assert from 'node:assert/strict';
import { recoveryPlan } from '../recovery-plan.mjs';
const family = {familyId:'family-01',childDeviceId:'child-01',parentUid:'p',childUid:'c',parentKey:'pk',childKey:'ck',parentRegisteredAt:1,childRegisteredAt:2,epoch:3,locked:true,updateTime:'family-time'};
const device = {fcmTokenGeneration:100,updateTime:'device-time',fcmToken:'never-log-or-export-this',lastLat:20};
test('Parent recovery plan preserves Child identity/token and invalidates old epoch', () => {
  const p=recoveryPlan(family,device,'parent',10,fresh());assert.equal(p.reviewOnly,true);assert.equal(p.commit.writes.length,2);
  const fields=p.commit.writes[1].update.fields;assert.equal(fields.childUid.stringValue,'c');assert.ok(!fields.parentUid);assert.equal(fields.epoch.integerValue,'4');
  assert.equal(p.commit.writes[1].currentDocument.updateTime,'family-time');assert.ok(!JSON.stringify(p).includes(device.fcmToken));
});
test('Child recovery invalidates token atomically but leaves location/events untouched', () => {
  const p=recoveryPlan(family,device,'child',10,fresh()), w=p.commit.writes[2];
  assert.equal(p.commit.writes[1].update.fields.parentUid.stringValue,'p');assert.ok(!p.commit.writes[1].update.fields.childUid);
  assert.equal(w.update.fields.fcmToken.stringValue,'');assert.equal(w.update.fields.fcmTokenOwnerUid.stringValue,'');assert.equal(w.update.fields.fcmTokenGeneration.integerValue,'101');
  assert.equal(w.currentDocument.updateTime,'device-time');assert.ok(!w.updateMask.fieldPaths.includes('lastLat'));assert.ok(p.commit.writes.every(w=>!w.delete));
});
test('Both lost identities require separate recovery, with no arbitrary family or missing CAS', () => {
  const p=recoveryPlan(family,device,'both',10,fresh());assert.ok(!p.commit.writes[1].update.fields.parentUid);assert.ok(!p.commit.writes[1].update.fields.childUid);
  for(const f of [{...family,familyId:'other'},{...family,updateTime:''},{...family,epoch:0}]) assert.throws(()=>recoveryPlan(f,device,'both'));
  assert.throws(()=>recoveryPlan(family,{},'child'));assert.throws(()=>recoveryPlan(family,device,'attacker'));
});

test('hash-only initial provisioning is fixed-family and create-only',()=>{
  const config=fresh(),p=bootstrapPlan(config,10);assert.equal(p.commit.writes[0].currentDocument.exists,false);
  const f=p.commit.writes[0].update.fields;assert.equal(f.parentBootstrapConsumed.booleanValue,false);assert.equal(f.childBootstrapConsumed.booleanValue,false);assert.equal(f.parentBootstrapHash.stringValue,config.roles.parent.sha256);
  assert.throws(()=>bootstrapPlan({...config,familyId:'other'},10));
  assert.throws(()=>bootstrapPlan({...config,roles:{parent:config.roles.parent,child:config.roles.parent}},10));
});
test('recovery requires fresh role hashes and keeps every previous capability retired',()=>{
  const config=fresh(),old=sha(),retired=sha(),f={...family,parentBootstrapHash:old,childBootstrapHash:sha(),parentBootstrapConsumed:true,childBootstrapConsumed:true,retiredBootstrapHashes:retired,parentClaimProofHash:sha()};
  assert.throws(()=>recoveryPlan(f,device,'parent',10));
  for(const hash of [old,retired,f.childBootstrapHash]) assert.throws(()=>recoveryPlan(f,device,'parent',10,{...config,roles:{parent:{sha256:hash,expiresAt:604800000}}}));
  const next=recoveryPlan(f,device,'parent',10,config).commit.writes[1].update.fields;
  assert.equal(next.parentBootstrapConsumed.booleanValue,false);assert.equal(next.childBootstrapConsumed.booleanValue,true);assert.ok(!next.parentClaimProofHash);assert.ok(next.retiredBootstrapHashes.stringValue.includes(old));
});

test('recovering Parent before pairing preserves the still-unclaimed Child capability',()=>{
  const hash=sha(),old=sha(),f={...family,childUid:'',childKey:'',childBootstrapHash:hash,childBootstrapConsumed:false,parentBootstrapHash:old,parentBootstrapConsumed:true};
  const fields=recoveryPlan(f,device,'parent',10,fresh()).commit.writes[1].update.fields;
  assert.equal(fields.childBootstrapHash.stringValue,hash);assert.equal(fields.childBootstrapConsumed.booleanValue,false);
  assert.ok(!fields.retiredBootstrapHashes.stringValue.split(',').includes(hash));assert.ok(fields.retiredBootstrapHashes.stringValue.split(',').includes(old));
});
