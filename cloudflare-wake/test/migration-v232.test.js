import test from 'node:test';
import assert from 'node:assert/strict';
import { migrationPlan, initialPlan, recoveryPlan, hash } from '../migration-plan-v232.mjs';
import { harness, installation, NOW } from './enrollment-fixture.mjs';
import { b64 } from '../src/google.js';
const config=()=>({familyId:'family-01',deviceId:'child-01',versionName:'2.3.2',versionCode:36,bootstrapMode:'UNTIL_CONSUMED_OR_REVOKED',roles:{parent:{sha256:hash('new-parent')},child:{sha256:hash('new-child')}}});
const snapshot=()=>({family:{familyId:'family-01',childDeviceId:'child-01',epoch:1,updateTime:'revision-before',parentBootstrapHash:hash('old-parent'),childBootstrapHash:hash('old-child'),parentBootstrapExpiresAt:NOW,childBootstrapExpiresAt:NOW,parentBootstrapConsumed:false,childBootstrapConsumed:false}});
test('migration removes initial expiry, retires both old hashes, CAS only family and preserves unrelated fields',()=>{
 const s=snapshot();s.family.operatorNote='retained';const p=migrationPlan(s,config()),w=p.commit.writes[0];
 assert.equal(p.conflictCheck,'NO CONFLICT');assert.equal(p.commit.writes.length,1);assert.deepEqual(w.currentDocument,{updateTime:'revision-before'});
 assert.equal(w.update.fields.operatorNote.stringValue,'retained');assert.equal(w.update.fields.schemaVersion.integerValue,'232');assert.equal(w.update.fields.epoch.integerValue,'2');
 for(const role of ['parent','child']) {assert.equal(w.update.fields[role+'BootstrapExpiresAt'],undefined);assert.ok(w.update.fields.retiredBootstrapHashes.stringValue.includes(s.family[role+'BootstrapHash']));}
});
test('migration rejects claimed, consumed, missing CAS or binding state',()=>{
 for(const mutation of [{parentUid:'p'},{childKey:'k'},{parentBootstrapConsumed:true},{childClaimProofHash:'proof'},{updateTime:''},{childDeviceBindingHash:'hash'}]) assert.throws(()=>migrationPlan({family:{...snapshot().family,...mutation}},config()),/CONFLICT/);
});
test('migration cannot reuse either retired v231 capability',()=>{
 const c=config();c.roles.parent.sha256=snapshot().family.childBootstrapHash;assert.throws(()=>migrationPlan(snapshot(),c),/Fresh/);
});
test('initial create-only proposal has no expiry even many months later',()=>{
 const p=initialPlan(config());assert.deepEqual(p.commit.writes[0].currentDocument,{exists:false});assert.ok(!JSON.stringify(p).includes('ExpiresAt'));assert.ok(!JSON.stringify(p).includes('new-parent'));
});
test('initial manifest rejects expiry/plaintext/cross-role scope',()=>{
 for(const c of [{...config(),expiresAt:NOW},{...config(),versionCode:35},{...config(),roles:{parent:{sha256:hash('x'),expiresAt:NOW},child:{sha256:hash('y')}}},{...config(),roles:{parent:{sha256:hash('x')},child:{sha256:hash('x')}}}]) assert.throws(()=>initialPlan(c));
});
test('old v231 initial capabilities rejected by actual registry after proposed CAS migration',async()=>{
 const h=harness(),c=config(),s=snapshot();s.family.parentBootstrapHash=hash(h.caps.parent);s.family.childBootstrapHash=hash(h.caps.child);
 const newTokens={parent:b64(crypto.getRandomValues(new Uint8Array(32))),child:b64(crypto.getRandomValues(new Uint8Array(32)))};
 for(const r of ['parent','child']) c.roles[r].sha256=hash(newTokens[r]);
 const p=migrationPlan(s,c);h.documents.set('families/family-01',{fields:p.commit.writes[0].update.fields,updateTime:'migrated'});
 for(const role of ['parent','child']) {
  const i=await installation('new-'+role,role);const proof=await h.proof(i,'register',{familyId:'family-01',deviceId:'child-01',version:'2.3.2',bootstrap:h.caps[role]});
  assert.equal((await h.call(i,role==='parent'?'registerParent':'registerChild',proof)).body.error,'invalid_bootstrap');
  const valid=await h.proof(i,'register',{familyId:'family-01',deviceId:'child-01',version:'2.3.2',bootstrap:newTokens[role]});assert.equal((await h.call(i,role==='parent'?'registerParent':'registerChild',valid)).status,200);
 }
});
test('offline operator recovery retires only selected identity and binding; short expiry isolated to recovery',()=>{
 const f={...snapshot().family,schemaVersion:232,bootstrapMode:'UNTIL_CONSUMED_OR_REVOKED',parentUid:'p',parentKey:'key',parentDeviceBindingHash:hash('binding'),childUid:'c',parentRebindGeneration:3};
 const p=recoveryPlan(f,{},'parent',{sha256:hash('fresh-recovery'),expiresAt:NOW+60000},NOW),w=p.commit.writes[0];
 assert.equal(w.update.fields.childUid.stringValue,'c');assert.equal(w.update.fields.parentUid,undefined);assert.equal(w.update.fields.parentDeviceBindingHash,undefined);assert.equal(w.update.fields.parentBootstrapMode.stringValue,'RECOVERY_EXPIRING');assert.equal(w.update.fields.parentRebindGeneration.integerValue,'4');assert.ok(w.update.fields.retiredUidHashes.stringValue.includes(hash('p')));assert.ok(w.update.fields.retiredBindingHashes.stringValue.includes(hash('binding')));
});
test('Child operator recovery clears FCM owner atomically with epoch and CAS, not coordinates/events',()=>{
 const f={...snapshot().family,schemaVersion:232},p=recoveryPlan(f,{updateTime:'child-revision',fcmTokenGeneration:99},'child',{sha256:hash('new-recovery'),expiresAt:NOW+60000},NOW),w=p.commit.writes.at(-1);
 assert.equal(p.commit.writes.length,3);assert.deepEqual(w.currentDocument,{updateTime:'child-revision'});assert.equal(w.update.fields.fcmTokenGeneration.integerValue,'100');assert.deepEqual(w.updateMask.fieldPaths.sort(),['fcmToken','fcmTokenGeneration','fcmTokenOwnerUid','fcmTokenUpdatedAt']);
});
test('operator recovery rejects old capability, long expiry and missing Child CAS',()=>{
 const f={...snapshot().family,schemaVersion:232};
 for(const cap of [{sha256:f.parentBootstrapHash,expiresAt:NOW+60000},{sha256:hash('fresh'),expiresAt:NOW+86400001}]) assert.throws(()=>recoveryPlan(f,{},'parent',cap,NOW));
 assert.throws(()=>recoveryPlan(f,{},'child',{sha256:hash('fresh'),expiresAt:NOW+60000},NOW));
});
