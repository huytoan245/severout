import test from 'node:test';
import assert from 'node:assert/strict';
import { WakeCoordinator, validateRequest, TTL } from '../src/coordinator.js';
import { FirebaseVerifier, b64, GoogleApi, ApiError, boundedFetch } from '../src/google.js';
import worker from '../src/worker.js';

const NOW = 1800000000000;
const body = id => ({ deviceId: 'child-01', requestId: String(id), requestedAt: id });
class Storage {
  constructor() { this.map = new Map(); this.alarmAt = null; }
  async get(k) { return structuredClone(this.map.get(k)); }
  async put(k, v) { this.map.set(k, structuredClone(v)); }
  async setAlarm(at) { this.alarmAt = at; }
  async deleteAlarm() { this.alarmAt = null; }
  async transaction(fn) { const before = structuredClone(this.map); const at = this.alarmAt; try { return await fn(this); } catch (e) { this.map = before; this.alarmAt = at; throw e; } }
}
function harness() {
  let now = NOW;
  const storage = new Storage(); const pending = [];
  const ctx = { storage, waitUntil: p => pending.push(p) };
  const api = {
    d: { refreshRequestedAt: NOW, refreshExpiresAt: NOW + TTL, refreshRequestedBy: 'parent', fcmTokenOwnerUid: 'child', fcmToken: 'token-A' }, sends: [], patches: [],
    async readFamily() { return {parentUid:'parent',childUid:'child',epoch:1,updateTime:'one'}; },
    async readDevice() { return structuredClone(this.d); },
    async patchIfCurrent(id, values) { if (this.d.refreshRequestedAt !== id) return false; this.patches.push(values); Object.assign(this.d, values); return true; },
    async send(token, id, ttl) { this.sends.push({ token, id, ttl }); return 'projects/family-location-884e5/messages/m'; }
  };
  const create = () => new WakeCoordinator(ctx, {}, api, () => now);
  let coordinator = create();
  return { storage, api, get c() { return coordinator; }, restart() { coordinator = create(); }, advance(ms) { now += ms; },
    async drain() { while (pending.length) await Promise.all(pending.splice(0)); },
    async post(id = NOW) { return coordinator.fetch(new Request('https://internal/v1/wake', { method: 'POST', body: JSON.stringify({command:body(id),binding:{parentUid:'parent',epoch:1}}) })); },
    async alarm() { now = storage.alarmAt; await coordinator.alarm(); }
  };
}
test('acceptance is durable before reply, and FCM payload has remaining TTL', async () => {
  const h = harness(); const r = await h.post(); assert.equal(r.status, 200); assert.equal((await r.json()).status, 'accepted');
  assert.equal((await h.storage.get('request')).id, NOW); await h.drain(); assert.equal(h.api.sends.length, 1); assert.equal(h.api.sends[0].ttl, TTL);
});
test('concurrent duplicates and restart do not send the same token twice', async () => {
  const h = harness(); await Promise.all(Array.from({ length: 6 }, () => h.post())); await h.drain();
  h.restart(); await h.post(); await h.drain(); await h.alarm(); assert.equal(h.api.sends.length, 1);
});
test('rotated token is woken by a persisted bounded receipt alarm', async () => {
  const h = harness(); await h.post(); await h.drain(); h.api.d.fcmToken = 'token-B'; h.restart(); await h.alarm();
  assert.deepEqual(h.api.sends.map(x => x.token), ['token-A', 'token-B']);
});
test('invalid old token is not resent/cleared, but a late rotated token is sent', async () => {
  const h = harness(); const send = h.api.send.bind(h.api); let invalidAttempts = 0;
  h.api.send = async (token, ...args) => { if (token === 'token-A') { invalidAttempts++; throw new ApiError('invalid_fcm_token'); } return send(token, ...args); };
  await h.post(); await h.drain(); await h.alarm(); assert.equal(invalidAttempts, 1); assert.equal(h.api.d.fcmToken, 'token-A');
  h.api.d.fcmToken = 'token-B'; h.restart(); await h.alarm(); assert.equal(h.api.sends[0].token, 'token-B');
});
test('Child receipt stops retry; dispatch is not GPS success', async () => {
  const h = harness(); await h.post(); await h.drain(); h.api.d.refreshReceivedFor = NOW; await h.alarm();
  assert.equal((await h.storage.get('request')).status, 'received'); assert.equal(h.storage.alarmAt, null); assert.equal(h.api.sends.length, 1);
});
test('missing token retries after object restart', async () => {
  const h = harness(); delete h.api.d.fcmToken; await h.post(); await h.drain(); assert.equal((await h.storage.get('request')).status, 'retrying');
  h.api.d.fcmToken = 'late-token'; h.restart(); await h.alarm(); assert.equal(h.api.sends[0].token, 'late-token');
});
test('old requestedBy or child owner fails closed', async () => {
  for (const field of ['refreshRequestedBy', 'fcmTokenOwnerUid']) { const h = harness(); h.api.d[field] = 'stranger'; await h.post(); await h.drain(); assert.equal(h.api.sends.length, 0); }
});
test('expired/future/device/token injection request rejected', () => {
  for (const value of [body(NOW - TTL), body(NOW + 31000), { ...body(NOW), deviceId: 'child-02' }, { ...body(NOW), token: 'arbitrary' }, { ...body(NOW), requestId: 'x' }, { ...body(NOW), requestedAt: '1800000000000' }]) assert.throws(() => validateRequest(value, NOW));
});
test('newer command supersedes old alarm without sending', async () => {
  const h = harness(); delete h.api.d.fcmToken; await h.post(); await h.drain(); h.api.d.refreshRequestedAt = NOW + 100;
  h.api.d.fcmToken = 'token'; await h.alarm(); assert.equal(h.api.sends.length, 0); assert.equal((await h.storage.get('request')).status, 'failed');
});
test('terminal GPS request does not send FCM', async () => {
  const h = harness(); h.api.d.refreshCompletedFor = NOW; await h.post(); await h.drain(); assert.equal(h.api.sends.length, 0); assert.equal(h.storage.alarmAt, null);
});
test('read-before-send detects token rotation in diagnostic window', async () => {
  const h = harness(); const patch = h.api.patchIfCurrent.bind(h.api);
  h.api.patchIfCurrent = async (...args) => { const ok = await patch(...args); h.api.d.fcmToken = 'B'; return ok; };
  await h.post(); await h.drain(); assert.equal(h.api.sends.length, 0); await h.alarm(); assert.equal(h.api.sends[0].token, 'B');
});
test('diagnostic outage after FCM acceptance never resends same token', async () => {
  const h = harness(); const patch = h.api.patchIfCurrent.bind(h.api); let fail = true;
  h.api.patchIfCurrent = async (id, v) => { if (v.wakeDispatchFor && fail) throw new Error('down'); return patch(id, v); };
  await h.post(); await h.drain(); assert.equal(h.api.sends.length, 1); fail = false; h.restart(); await h.alarm(); assert.equal(h.api.sends.length, 1);
});
test('at most eight attempts; no perpetual alarm or repeated same-token sends', async () => {
  const h = harness(); await h.post(); await h.drain(); while (h.storage.alarmAt != null) await h.alarm();
  assert.equal((await h.storage.get('request')).attempts, 8); assert.equal((await h.storage.get('request')).status, 'sent_unconfirmed'); assert.equal(h.api.sends.length, 1);
});
test('transient outage bounded to eight attempts', async () => {
  const h = harness(); h.api.readDevice = async () => { throw new Error('offline'); }; await h.post(); await h.drain(); while (h.storage.alarmAt != null) await h.alarm();
  assert.equal((await h.storage.get('request')).attempts, 8); assert.equal(h.api.sends.length, 0);
});
test('rate limit is persisted across object restart', async () => {
  const h = harness(); for (let i = 0; i < 12; i++) { assert.equal((await h.post()).status, 200); await h.drain(); }
  h.restart(); const r = await h.post(); assert.equal(r.status, 429); assert.equal(r.headers.get('retry-after'), '60'); assert.equal(h.api.sends.length, 1);
});
test('TTL stops pending alarm after long outage', async () => {
  const h = harness(); await h.post(); await h.drain(); h.advance(TTL); await h.c.alarm(); assert.equal((await h.storage.get('request')).status, 'expired'); assert.equal(h.storage.alarmAt, null);
});

const keys = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
const jwk = { ...await crypto.subtle.exportKey('jwk', keys.publicKey), kid: 'key-1', alg: 'RS256' };
const encoder = new TextEncoder();
async function jwt(overrides = {}, header = {}) {
  const h = b64(encoder.encode(JSON.stringify({ alg: 'RS256', kid: 'key-1', ...header })));
  const p = b64(encoder.encode(JSON.stringify({ aud: 'family-location-884e5', iss: 'https://securetoken.google.com/family-location-884e5', sub: 'parent', exp: NOW / 1000 + 3600, iat: NOW / 1000 - 10, auth_time: NOW / 1000 - 100, ...overrides })));
  return h + '.' + p + '.' + b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', keys.privateKey, encoder.encode(h + '.' + p)));
}
function verifier() { return new FirebaseVerifier(async () => Response.json({ keys: [jwk] }, { headers: { 'cache-control': 'max-age=3600' } })); }
test('cryptographically valid Firebase token and exact Parent UID accepted', async () => { assert.equal(await verifier().verify(await jwt(), 'parent', NOW), 'parent'); });
test('JWT subject is derived without a configured UID, but remains cryptographically verified', async () => {
  assert.equal(await verifier().verify(await jwt({sub:'dynamic-anonymous-uid'}), undefined, NOW), 'dynamic-anonymous-uid');
  await assert.rejects(async()=>verifier().verify(await jwt({sub:42}), undefined, NOW), e=>e.status===401);
});
test('Google HTTP transport rejects redirects and uses workerd-supported manual mode', async () => {
  let calls=0;
  await assert.rejects(()=>boundedFetch('https://google.example', {}, async (_url,options)=>{calls++;assert.equal(options.redirect,'manual');return new Response('',{status:302,headers:{Location:'https://attacker.example'}});}),e=>e.code==='upstream_unavailable');
  assert.equal(calls,1);
});
test('an epoch change stops a pending wake and cannot reuse old Parent authority', async () => {
  const h=harness();delete h.api.d.fcmToken;await h.post();await h.drain();
  h.api.readFamily=async()=>({parentUid:'replacement',childUid:'child',epoch:2,updateTime:'two'});
  await h.alarm();assert.equal(h.api.sends.length,0);assert.equal((await h.storage.get('request')).error,'family_changed');
  assert.equal((await h.post()).status,403);
});
test('forged signature, wrong UID/project/issuer/expiry/alg/key rejected', async () => {
  const values = [await jwt({ sub: 'child' }), await jwt({ aud: 'other' }), await jwt({ iss: 'https://evil' }), await jwt({ exp: NOW / 1000 }), await jwt({ iat: NOW / 1000 + 60 }), await jwt({ auth_time: NOW / 1000 + 60 }), await jwt({ exp: NOW / 1000 - 100 }), await jwt({ exp: NOW / 1000 + 7200 }), await jwt({ auth_time: NOW / 1000 + 25, iat: NOW / 1000 - 100 }), await jwt({}, { alg: 'HS256' }), await jwt({}, { kid: 'unknown' })];
  const valid = await jwt(); values.push(valid.slice(0, -8) + 'AAAAAAA');
  for (const token of values) await assert.rejects(() => verifier().verify(token, 'parent', NOW), e => e.status === 401);
});
test('public keys cached; keys outage is a retryable error, not valid auth', async () => {
  let calls = 0; const v = new FirebaseVerifier(async () => { calls++; return Response.json({ keys: [jwk] }, { headers: { 'cache-control': 'max-age=3600' } }); });
  await v.verify(await jwt(), 'parent', NOW); await v.verify(await jwt(), 'parent', NOW); assert.equal(calls, 1);
  await assert.rejects(() => new FirebaseVerifier(async () => new Response('', { status: 503 })).verify(awaitToken, 'parent', NOW), e => e.status === 503);
});
const awaitToken = await jwt();
test('public health reveals no secrets; wake fails closed without config', async () => {
  const r = await worker.fetch(new Request('https://x/health'), {}); assert.deepEqual(await r.json(), { service: 'family-location-wake', version: '2.3.2', configured: false });
  const denied = await worker.fetch(new Request('https://x/v1/wake', { method: 'POST', body: '{}' }), {}); assert.equal(denied.status, 503);
});
test('configured endpoints reject unauthenticated payloads before any storage access', async () => {
  const env = { FIREBASE_PROJECT_ID: 'family-location-884e5', GOOGLE_SERVICE_ACCOUNT_JSON: '{}' };
  for (const [b, status] of [[body(Date.now()), 401], [{ ...body(Date.now()), token: 't' }, 401], ['x'.repeat(8193), 401]]) {
    const r = await worker.fetch(new Request('https://x/v1/wake', { method: 'POST', headers: { 'content-type': 'application/json' }, body: typeof b === 'string' ? b : JSON.stringify(b) }), env); assert.equal(r.status, status);
  }
});
test('Firestore diagnostic uses updateTime precondition and preserves newer command', async () => {
  const api = new GoogleApi({}); const calls = []; api.call = async (url, opts) => {
    calls.push({ url, opts }); return opts?.method === 'PATCH' ? Response.json({}) : Response.json({ updateTime: '2026-10-03T01:00:00Z', fields: { refreshRequestedAt: { integerValue: String(NOW) } } });
  };
  assert.equal(await api.patchIfCurrent(NOW + 1, { wakeDispatchFor: NOW + 1 }), false); assert.equal(calls.length, 1);
  assert.equal(await api.patchIfCurrent(NOW, { wakeDispatchFor: NOW }), true); assert.match(calls[2].url, /currentDocument.updateTime=/);
});
test('FCM HTTP v1 uses HIGH, correct project, server token and remaining TTL', async () => {
  const api = new GoogleApi({}); let payload; api.call = async (url, opts) => { assert.equal(url, 'https://fcm.googleapis.com/v1/projects/family-location-884e5/messages:send'); payload = JSON.parse(opts.body); return Response.json({ name: 'message' }); };
  assert.equal(await api.send('server-token', NOW, 90000), 'message'); assert.equal(payload.message.token, 'server-token'); assert.equal(payload.message.android.priority, 'HIGH'); assert.equal(payload.message.android.ttl, '90s'); assert.equal(payload.message.data.deviceId,'child-01'); assert.equal(payload.message.data.requestedAt,String(NOW)); assert.equal(payload.message.data.expiresAt,String(NOW+TTL));
});
