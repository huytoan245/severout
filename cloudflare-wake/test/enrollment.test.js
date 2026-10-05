import test from 'node:test';
import assert from 'node:assert/strict';
import { FamilyRegistry, digest, proofMessage } from '../src/enrollment.js';
import { GoogleApi, b64 } from '../src/google.js';
import { readBody } from '../src/worker.js';
const NOW = 1800000000000;
class Storage {
  constructor() { this.map = new Map(); }
  async get(k) { return structuredClone(this.map.get(k)); }
  async put(k, v) { this.map.set(k, structuredClone(v)); }
  async delete(k) { this.map.delete(k); }
  async list() { return structuredClone(this.map); }
  async setAlarm(t) { this.alarm = t; }
  async getAlarm() { return this.alarm ?? null; }
  async transaction(fn) { const map = structuredClone(this.map), alarm = this.alarm; try { return await fn(this); } catch (e) { this.map = map; this.alarm = alarm; throw e; } }
}
async function installation(uid, role) {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  return { uid, role, pair, publicKey: b64(await crypto.subtle.exportKey('spki', pair.publicKey)) };
}
function harness() {
  const storage = new Storage(), documents = new Map(), sent = [];
  let revision = 0, now = NOW, offline = false, crash = false;
  const api = new GoogleApi({});
  api.call = async (url, options = {}) => {
    if (offline) throw new Error('offline');
    if (url.endsWith('/documents:commit')) {
      const writes = JSON.parse(options.body).writes;
      for (const w of writes) {
        const path = w.update.name.split('/documents/')[1], previous = documents.get(path);
        if ((w.currentDocument.updateTime && previous?.updateTime !== w.currentDocument.updateTime) || (w.currentDocument.exists === false && previous)) return new Response('', { status: 409 });
      }
      for (const w of writes) {
        const path = w.update.name.split('/documents/')[1];
        documents.set(path, { fields: { ...documents.get(path)?.fields, ...w.update.fields }, updateTime: String(++revision) });
      }
      if (crash) { crash = false; throw new Error('response_lost_after_commit'); }
      return Response.json({});
    }
    const parsed = new URL(url), path = parsed.pathname.split('/documents/')[1], previous = documents.get(path);
    if (options.method === 'PATCH') {
      const time = parsed.searchParams.get('currentDocument.updateTime');
      if ((time && previous?.updateTime !== time) || (parsed.searchParams.get('currentDocument.exists') === 'false' && previous)) return new Response('', { status: 409 });
      documents.set(path, { fields: JSON.parse(options.body).fields, updateTime: String(++revision) });
      if (crash) { crash = false; throw new Error('response_lost_after_commit'); }
      return Response.json({});
    }
    return previous ? Response.json(previous) : new Response('', { status: 404 });
  };
  const env = { ENROLLMENT_ENABLED: 'true', WAKE_STATE: { idFromName: n => n, get: () => ({ fetch: async r => { sent.push(await r.json()); return Response.json({ requestId: String(NOW), status: 'accepted' }); } }) } };
  let registry = new FamilyRegistry({ storage }, env, api, () => now);
  const call = async (who, operation, body = {}) => {
    const response = await registry.fetch(new Request('https://internal', { method: 'POST', body: JSON.stringify({ uid: who.uid, operation, body }) }));
    return { status: response.status, body: await response.json() };
  };
  const proof = async (who, purpose, payload) => {
    const c = await call(who, 'challenge', { familyId: 'family-01', deviceId: 'child-01', role: who.role, purpose });
    if (c.status !== 200) return c;
    const raw = JSON.stringify(payload);
    const signature = b64(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, who.pair.privateKey, new TextEncoder().encode(proofMessage(who.uid, who.role, c.body.nonce, purpose, await digest(raw)))));
    return { nonce: c.body.nonce, signature, publicKey: who.publicKey, payload: raw };
  };
  const enroll = async who => { const p = await proof(who, 'register', { familyId: 'family-01', deviceId: 'child-01', version: '2.3.1' }); return p.status ? p : call(who, who.role === 'parent' ? 'registerParent' : 'registerChild', p); };
  return { api, env, storage, documents, sent, call, proof, enroll, offline(v) { offline = v; }, crashAfterCommit() { crash = true; }, advance(ms) { now += ms; }, restart() { registry = new FamilyRegistry({ storage }, env, api, () => now); }, otherInstance() { const other = new FamilyRegistry({ storage: new Storage() }, env, api, () => now); return async (who, operation, body) => { const r = await other.fetch(new Request('https://internal', { method: 'POST', body: JSON.stringify({ uid: who.uid, operation, body }) })); return { status: r.status, body: await r.json() }; }; } };
}
const tokenPayload = (token, generation) => ({ familyId: 'family-01', deviceId: 'child-01', token, generation });
const wakePayload = () => ({ deviceId: 'child-01', requestId: String(NOW), requestedAt: NOW });

for (const order of [['child', 'parent'], ['parent', 'child']]) test(`A/B/C fresh auto-pair with ${order[0]} installed first`, async () => {
  const h = harness(), apps = { parent: await installation('p', 'parent'), child: await installation('c', 'child') };
  const first = await h.enroll(apps[order[0]]); assert.equal(first.status, 200); assert.equal(first.body.paired, false);
  const second = await h.enroll(apps[order[1]]); assert.equal(second.status, 200); assert.equal(second.body.paired, true);
  const f = await h.api.readFamily(); assert.equal(f.locked, true); assert.equal(f.parentUid, 'p'); assert.equal(f.childUid, 'c');
  assert.equal((await h.call(apps.parent, 'state')).body.paired, true);
});
test('D/E/F concurrent candidates: exactly one slot each, third Parent/Child rejected', async () => {
  const h = harness(), candidates = await Promise.all(['p1', 'p2', 'p3', 'c1', 'c2', 'c3'].map(uid => installation(uid, uid[0] === 'p' ? 'parent' : 'child')));
  const results = await Promise.all(candidates.map(c => h.enroll(c)));
  assert.equal(results.filter(r => r.status === 200).length, 2);
  assert.equal(results.filter(r => r.status === 403).length, 4);
  const f = await h.api.readFamily(); assert.ok(f.parentUid); assert.ok(f.childUid); assert.equal(f.locked, true);
});
test('CAS protects the slot against a competing registry instance', async () => {
  const h = harness(), a = await installation('a', 'parent'), b = await installation('b', 'parent'), other = h.otherInstance();
  const pa = await h.proof(a, 'register', { familyId: 'family-01', deviceId: 'child-01', version: '2.3.1' });
  const cb = await other(b, 'challenge', { familyId: 'family-01', deviceId: 'child-01', role: 'parent', purpose: 'register' });
  const raw = pa.payload, pb = { nonce: cb.body.nonce, publicKey: b.publicKey, payload: raw, signature: b64(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, b.pair.privateKey, new TextEncoder().encode(proofMessage(b.uid, b.role, cb.body.nonce, 'register', await digest(raw))))) };
  const result = await Promise.all([h.call(a, 'registerParent', pa), other(b, 'registerParent', pb)]);
  assert.equal(result.filter(x => x.status === 200).length, 1);
  assert.ok([403, 409].includes(result.find(x => x.status !== 200).status));
});
test('one UID cannot own both roles', async () => {
  const h = harness(), parent = await installation('same', 'parent'); await h.enroll(parent);
  assert.equal((await h.enroll({ ...parent, role: 'child' })).body.error, 'role_conflict');
});
test('G token rotation is atomic, monotonic, idempotent and keeps cloud history', async () => {
  const h = harness(), child = await installation('c', 'child'); await h.enroll(child);
  h.documents.set('devices/child-01', { updateTime: 'seed', fields: { lastLat: { integerValue: '20' }, fcmTokenGeneration: { integerValue: '100' } } });
  for (const [token, gen, status] of [['old-token-0000', 50, 409], ['current-token-0000', 101, 200], ['rotated-token-0000', 102, 200], ['delayed-token-0000', 101, 409], ['same-revision-token', 102, 409], ['rotated-token-0000', 102, 200]]) {
    const p = await h.proof(child, 'token', tokenPayload(token, gen)); const r = await h.call(child, 'token', p); assert.equal(r.status, status);
    if (status === 409) assert.ok(r.body.requiredGeneration >= 101);
  }
  const d = await h.api.readDocument(h.api.docUrl()); assert.equal(d.lastLat, 20); assert.equal(d.fcmTokenGeneration, 102); assert.equal(d.fcmTokenOwnerUid, 'c');
});
test('H/J/K restart and response loss during registration keep the same owner', async () => {
  const h = harness(), parent = await installation('p', 'parent');
  const proof = await h.proof(parent, 'register', { familyId: 'family-01', deviceId: 'child-01', version: '2.3.1' });
  h.crashAfterCommit(); assert.equal((await h.call(parent, 'registerParent', proof)).status, 503);
  h.restart(); assert.equal((await h.call(parent, 'registerParent', proof)).status, 200);
  assert.equal((await h.call(parent, 'registerParent', proof)).status, 200);
  assert.equal((await h.enroll(parent)).status, 200); assert.equal((await h.api.readFamily()).parentUid, 'p');
});
test('I offline initial registration retries and eventually pairs without operator UID input', async () => {
  const h = harness(), parent = await installation('p', 'parent'), child = await installation('c', 'child');
  h.offline(true); assert.equal((await h.enroll(parent)).status, 503);
  h.offline(false); h.restart(); assert.equal((await h.enroll(parent)).status, 200); assert.equal((await h.enroll(child)).body.paired, true);
});
test('L retained UID/key across APK update and reboot; full uninstall/new key rejected', async () => {
  const h = harness(), child = await installation('c', 'child'); await h.enroll(child); h.restart();
  assert.equal((await h.enroll(child)).status, 200);
  assert.equal((await h.enroll(await installation('new-uid', 'child'))).status, 403);
  assert.equal((await h.enroll(await installation('c', 'child'))).status, 403);
});
test('M/N unauthorized UID cannot wake or replace token; Parent cannot rotate Child token', async () => {
  const h = harness(), parent = await installation('p', 'parent'), child = await installation('c', 'child'); await h.enroll(parent); await h.enroll(child);
  const stranger = await installation('outsider', 'parent'); assert.equal((await h.proof(stranger, 'wake', wakePayload())).status, 403);
  assert.equal((await h.proof({ ...stranger, role: 'child' }, 'token', tokenPayload('evil-token-0000', 999))).status, 403);
  assert.equal((await h.proof(parent, 'token', tokenPayload('evil-token-0000', 999))).status, 400);
  const p = await h.proof(parent, 'wake', wakePayload()); assert.equal((await h.call(parent, 'wake', p)).status, 200);
  assert.deepEqual(h.sent[0].binding, { parentUid: 'p', epoch: 1 });
  assert.equal((await h.call(child, 'wake', p)).status, 403);
});
test('modified payload/key/signature and nonce replay with changed operation rejected', async () => {
  const h = harness(), child = await installation('c', 'child'); await h.enroll(child);
  const p = await h.proof(child, 'token', tokenPayload('good-token-0000', 1));
  assert.equal((await h.call(child, 'token', { ...p, payload: JSON.stringify(tokenPayload('evil-token-0000', 99)) })).status, 403);
  assert.equal((await h.call(child, 'token', p)).status, 200);
  assert.equal((await h.call(child, 'token', p)).status, 200);
  assert.equal((await h.call(child, 'token', { ...p, signature: 'a'.repeat(86) })).status, 409);
});
test('expired challenge and pre-recovery cached proof fail closed', async () => {
  const h = harness(), parent = await installation('p', 'parent'); await h.enroll(parent);
  const p = await h.proof(parent, 'register', { familyId: 'family-01', deviceId: 'child-01', version: '2.3.1' });
  assert.equal((await h.call(parent, 'registerParent', p)).status, 200);
  h.documents.get('families/family-01').fields.epoch = { integerValue: '2' };
  assert.equal((await h.call(parent, 'registerParent', p)).body.error, 'family_changed');
  h.advance(121000); assert.equal((await h.call(parent, 'registerParent', p)).body.error, 'invalid_nonce');
});
test('closed enrollment, fixed IDs, malformed and oversized bodies fail closed', async () => {
  const h = harness(), child = await installation('c', 'child'); h.env.ENROLLMENT_ENABLED = 'false';
  assert.equal((await h.enroll(child)).body.error, 'enrollment_closed');
  assert.equal((await h.call(child, 'challenge', { familyId: 'other', deviceId: 'child-01', role: 'child', purpose: 'register' })).status, 400);
  assert.equal((await h.call(child, 'registerChild', { uid: 'chosen' })).status, 400);
  for (const body of ['x', 'x'.repeat(8193)]) await assert.rejects(() => readBody(new Request('https://x', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })), e => [400, 413].includes(e.status));
});
test('rate limiting survives backend restart and expired nonce storage is pruned', async () => {
  const h = harness(), child = await installation('c', 'child'); await h.enroll(child);
  for (let i = 0; i < 30; i++) assert.equal((await h.call(child, 'state')).status, 200);
  h.restart(); assert.equal((await h.call(child, 'state')).status, 429);
  h.advance(181000); const r = new FamilyRegistry({ storage: h.storage }, h.env, h.api, () => NOW + 181000); await r.alarm();
  assert.equal([...h.storage.map.keys()].filter(x => x.startsWith('nonce:')).length, 0);
  assert.equal((await h.call(child, 'state')).status, 200);
});
test('retired identity cannot reclaim a slot reopened by an explicit operator recovery', async () => {
  const h = harness(), oldParent = await installation('old-parent', 'parent'); await h.enroll(oldParent);
  const f = h.documents.get('families/family-01');
  f.fields.epoch = { integerValue: '2' }; f.fields.retiredUidHashes = { stringValue: await digest('old-parent') };
  delete f.fields.parentUid; delete f.fields.parentKey;
  assert.equal((await h.enroll(oldParent)).body.error, 'retired_identity');
  assert.equal((await h.enroll(await installation('replacement-parent', 'parent'))).status, 200);
});
test('steady traffic does not postpone nonce cleanup forever', async () => {
  const h = harness(), child = await installation('c', 'child'); await h.enroll(child);
  const firstAlarm = h.storage.alarm; h.advance(60000); await h.call(child, 'state');
  assert.equal(h.storage.alarm, firstAlarm);
});
