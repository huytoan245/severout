import test from 'node:test';
import assert from 'node:assert/strict';
import { recoveryPlan } from '../recovery-plan.mjs';
const family = {familyId:'family-01',childDeviceId:'child-01',parentUid:'p',childUid:'c',parentKey:'pk',childKey:'ck',parentRegisteredAt:1,childRegisteredAt:2,epoch:3,locked:true,updateTime:'family-time'};
const device = {fcmTokenGeneration:100,updateTime:'device-time',fcmToken:'never-log-or-export-this',lastLat:20};
test('Parent recovery plan preserves Child identity/token and invalidates old epoch', () => {
  const p=recoveryPlan(family,device,'parent',10);assert.equal(p.reviewOnly,true);assert.equal(p.commit.writes.length,2);
  const fields=p.commit.writes[1].update.fields;assert.equal(fields.childUid.stringValue,'c');assert.ok(!fields.parentUid);assert.equal(fields.epoch.integerValue,'4');
  assert.equal(p.commit.writes[1].currentDocument.updateTime,'family-time');assert.ok(!JSON.stringify(p).includes(device.fcmToken));
});
test('Child recovery invalidates token atomically but leaves location/events untouched', () => {
  const p=recoveryPlan(family,device,'child',10), w=p.commit.writes[2];
  assert.equal(p.commit.writes[1].update.fields.parentUid.stringValue,'p');assert.ok(!p.commit.writes[1].update.fields.childUid);
  assert.equal(w.update.fields.fcmToken.stringValue,'');assert.equal(w.update.fields.fcmTokenOwnerUid.stringValue,'');assert.equal(w.update.fields.fcmTokenGeneration.integerValue,'101');
  assert.equal(w.currentDocument.updateTime,'device-time');assert.ok(!w.updateMask.fieldPaths.includes('lastLat'));assert.ok(p.commit.writes.every(w=>!w.delete));
});
test('Both lost identities require separate recovery, with no arbitrary family or missing CAS', () => {
  const p=recoveryPlan(family,device,'both',10);assert.ok(!p.commit.writes[1].update.fields.parentUid);assert.ok(!p.commit.writes[1].update.fields.childUid);
  for(const f of [{...family,familyId:'other'},{...family,updateTime:''},{...family,epoch:0}]) assert.throws(()=>recoveryPlan(f,device,'both'));
  assert.throws(()=>recoveryPlan(family,{},'child'));assert.throws(()=>recoveryPlan(family,device,'attacker'));
});
