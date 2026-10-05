import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc } from 'firebase/firestore';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { GoogleApi } from '../src/google.js';
const env = await initializeTestEnvironment({ projectId: 'demo-family-location-wake', firestore: { host: '127.0.0.1', port: 8086, rules: readFileSync('.wrangler/test-fixture.rules', 'utf8') } });
await env.clearFirestore(); // Reset localhost demo fixtures only, never a cloud project.
let count = 0;
const pass = async action => { await assertSucceeds(action); count++; };
const deny = async action => { await assertFails(action); count++; };
try {
  const parent = env.authenticatedContext('test-parent').firestore();
  const child = env.authenticatedContext('test-child').firestore();
  const stranger = env.authenticatedContext('stranger').firestore();
  const anonymous = env.unauthenticatedContext().firestore();
  const path = 'devices/child-01'; const now = Date.now();
  await env.withSecurityRulesDisabled(async context => { await setDoc(doc(context.firestore(), 'families/family-01'), {familyId:'family-01',childDeviceId:'child-01',parentUid:'test-parent',childUid:'test-child',epoch:1,locked:true}); await setDoc(doc(context.firestore(), path), { refreshRequestedAt: 1, fcmTokenGeneration: 1, fcmToken: 'old', fcmTokenOwnerUid: 'test-child' }); });
  await pass(getDoc(doc(parent, path))); await pass(getDoc(doc(child, path)));
  await deny(getDoc(doc(stranger, path))); await deny(getDoc(doc(anonymous, path)));
  await pass(setDoc(doc(parent, path), { refreshRequestedAt: now, refreshExpiresAt: now + 900000, refreshRequestedBy: 'test-parent' }, { merge: true }));
  await deny(setDoc(doc(parent, path), { fcmToken: 'attacker-token' }, { merge: true }));
  await deny(setDoc(doc(stranger, path), { fcmToken: 'attacker-token', fcmTokenOwnerUid: 'test-child', fcmTokenGeneration: 100 }, { merge: true }));
  await deny(setDoc(doc(parent, path), { refreshRequestedAt: now - 1, refreshExpiresAt: now - 1 + 900000, refreshRequestedBy: 'test-parent' }, { merge: true }));
  await deny(setDoc(doc(parent, path), { refreshRequestedBy: 'test-child' }, { merge: true }));
  await deny(setDoc(doc(child, path), { fcmToken: 'new', fcmTokenGeneration: 2, fcmTokenOwnerUid: 'test-child' }, { merge: true }));
  await deny(setDoc(doc(child, path), { fcmToken: 'old-delayed', fcmTokenGeneration: 1 }, { merge: true }));
  await deny(setDoc(doc(child, path), { fcmTokenOwnerUid: 'test-parent' }, { merge: true }));
  await deny(setDoc(doc(child, path), { refreshRequestedAt: now + 1 }, { merge: true }));
  await deny(setDoc(doc(parent, path), { wakeDispatchResult: 'sent' }, { merge: true }));
  await deny(setDoc(doc(child, path), { wakeDispatchResult: 'sent' }, { merge: true }));
  await pass(setDoc(doc(child, path), { refreshReceivedFor: now, refreshCompletedFor: now, lastLat: 20.0, heartbeatAt: now }, { merge: true }));
  await pass(setDoc(doc(parent, path), { locationReminderRequestedAt: now, locationReminderExpiresAt: now + 900000 }, { merge: true }));
  await pass(setDoc(doc(child, path), { locationReminderAckFor: now, locationReminderResult: 'shown' }, { merge: true }));
  await pass(setDoc(doc(child, path + '/events/id'), { type: 'location_sample', id: 'sample-id', time: now, lat: 20.0, lon: 105.0 }));
  await pass(getDoc(doc(parent, path + '/events/id')));
  await deny(setDoc(doc(parent, path + '/events/id2'), { type: 'location_sample', id: 'spoof', time: now }));
  await deny(setDoc(doc(child, 'devices/child-02'), { heartbeatAt: now }));
  await deny(deleteDoc(doc(parent, path))); await deny(deleteDoc(doc(child, path + '/events/id')));
  await deny(setDoc(doc(child,path),{ refreshReceivedFor: now-1 },{merge:true}));
  await deny(setDoc(doc(child,path),{ refreshResult:'locating' },{merge:true}));
  await pass(setDoc(doc(child,path),{ locationTime:now },{merge:true}));
  await deny(setDoc(doc(child,path),{ locationTime:now-1 },{merge:true}));
  await deny(setDoc(doc(child,path),{ fcmToken:'same-revision-other-token',fcmTokenGeneration:2 },{merge:true}));
  await deny(setDoc(doc(child,path),{ fcmToken:'clean-install-token',fcmTokenGeneration:3000,fcmTokenOwnerUid:'test-child' },{merge:true}));
  await pass(setDoc(doc(child,path+'/events/id'),{type:'location_sample',id:'sample-id',time:now,lat:20.0,lon:105.0}));
  await deny(setDoc(doc(child,path+'/events/id'),{type:'location_sample',id:'sample-id',time:now-1,lat:20.0,lon:105.0}));
  await pass(getDoc(doc(parent,'families/family-01')));
  await pass(getDoc(doc(child,'families/family-01')));
  await deny(getDoc(doc(stranger,'families/family-01')));
  for (const db of [parent,child,stranger]) { await deny(setDoc(doc(db,'families/family-01'), {parentUid:'stranger'}, {merge:true})); await deny(deleteDoc(doc(db,'families/family-01'))); }
  await deny(setDoc(doc(child,path),{parentUid:'stranger'}, {merge:true}));
  await deny(setDoc(doc(stranger,'families/other'),{childUid:'stranger'}));
  // Exercise the actual REST commit wire format on the real emulator,
  // including atomic family membership + device updateTime preconditions.
  const api = new GoogleApi({});
  api.call = async (url, options = {}) => fetch(url.replace('https://firestore.googleapis.com','http://127.0.0.1:8086').replaceAll('family-location-884e5','demo-family-location-wake'), {
    ...options, body: options.body?.replaceAll('family-location-884e5','demo-family-location-wake'),
    headers: { Authorization: 'Bearer owner', 'Content-Type':'application/json' }
  });
  let family = await api.readFamily();
  const result = await api.updateChildToken(family,'test-child',{token:'emulator-token-0000',generation:100},now); assert.equal(result.confirmed,true); count++;
  family=await api.readFamily();
  await assert.rejects(()=>api.updateChildToken(family,'test-parent',{token:'parent-token-0000',generation:101},now), e=>e.status===403); count++;
  await assert.rejects(()=>api.updateChildToken(family,'test-child',{token:'delayed-token-0000',generation:99},now), e=>e.status===409); count++;
  await assert.rejects(()=>api.updateChildToken(family,'test-child',{token:'different-token-0000',generation:100},now), e=>e.status===409); count++;
  assert.equal((await api.updateChildToken(family,'test-child',{token:'emulator-token-0000',generation:100},now)).confirmed,true); count++;
  await env.withSecurityRulesDisabled(async context=>{ await setDoc(doc(context.firestore(),'families/family-01'),{epoch:2},{merge:true}); });
  await assert.rejects(()=>api.updateChildToken(family,'test-child',{token:'new-token-0000',generation:101},now)); count++;
  assert.equal((await api.readDocument(api.docUrl())).fcmTokenGeneration,100); count++;
  await deny(setDoc(doc(child,path),{fcmToken:'direct-sdk-token',fcmTokenGeneration:102},{merge:true}));
  console.log(`PASS: ${count} actual Firestore emulator security-rule assertions. Fixture UIDs/project only; no production writes.`);
} finally { await env.cleanup(); }
