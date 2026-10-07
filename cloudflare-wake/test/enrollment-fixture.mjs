import { createHash } from 'node:crypto';
import { FamilyRegistry, digest, proofMessage } from '../src/enrollment.js';
import { GoogleApi, b64 } from '../src/google.js';
import { readBody } from '../src/worker.js';
export const NOW = 1800000000000;
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
export async function installation(uid, role, material) {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  return { uid, role, material:material || await digest('fixture-device:'+role+':'+uid), pair, publicKey: b64(await crypto.subtle.exportKey('spki', pair.publicKey)) };
}
export function harness() {
  const storage = new Storage(), documents = new Map(), sent = [];
  let revision = 0, now = NOW, offline = false, crash = false, failWrite = false;
  // Fresh test capabilities in memory only, never literal fixture secrets.
  const caps = { parent: b64(crypto.getRandomValues(new Uint8Array(32))), child: b64(crypto.getRandomValues(new Uint8Array(32))) };
  const hash = token => createHash('sha256').update(token).digest('base64url');
  documents.set('families/family-01', { updateTime:'provisioned', fields:{
    familyId:{stringValue:'family-01'}, childDeviceId:{stringValue:'child-01'}, epoch:{integerValue:'1'}, schemaVersion:{integerValue:'232'}, bootstrapMode:{stringValue:'UNTIL_CONSUMED_OR_REVOKED'},
    ...Object.fromEntries(['parent','child'].flatMap(role=>[[role+'BootstrapHash',{stringValue:hash(caps[role])}], [role+'BootstrapConsumed',{booleanValue:false}]]))
  }});
  const api = new GoogleApi({});
  api.call = async (url, options = {}) => {
    if (offline) throw new Error('offline');
    if (url.endsWith('/documents:commit')) {
      if (failWrite) { failWrite=false; throw new Error('before_atomic_write'); }
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
      if (failWrite) { failWrite=false; throw new Error('before_atomic_write'); }
      const time = parsed.searchParams.get('currentDocument.updateTime');
      if ((time && previous?.updateTime !== time) || (parsed.searchParams.get('currentDocument.exists') === 'false' && previous)) return new Response('', { status: 409 });
      documents.set(path, { fields: JSON.parse(options.body).fields, updateTime: String(++revision) });
      if (crash) { crash = false; throw new Error('response_lost_after_commit'); }
      return Response.json({});
    }
    return previous ? Response.json(previous) : new Response('', { status: 404 });
  };
  const env = { ENROLLMENT_ENABLED: 'true', DEVICE_BINDING_PEPPER:b64(crypto.getRandomValues(new Uint8Array(32))), WAKE_STATE: { idFromName: n => n, get: () => ({ fetch: async r => { sent.push(await r.json()); return Response.json({ requestId: String(NOW), status: 'accepted' }); } }) } };
  let registry = new FamilyRegistry({ storage }, env, api, () => now);
  const call = async (who, operation, body = {}) => {
    const response = await registry.fetch(new Request('https://internal', { method: 'POST', body: JSON.stringify({ uid: who.uid, operation, body }) }));
    return { status: response.status, body: await response.json() };
  };
  const proof = async (who, purpose, payload) => {
    const c = await call(who, 'challenge', { familyId: 'family-01', deviceId: 'child-01', role: who.role, purpose });
    if (c.status !== 200) return c;
    if (['register','rebind'].includes(purpose) && !Object.hasOwn(payload,'deviceRecoveryMaterial')) payload={...payload,deviceRecoveryMaterial:who.material};
    const raw = JSON.stringify(purpose === 'register' && c.body.needsBootstrap && !Object.hasOwn(payload,'bootstrap') ? {...payload,bootstrap:caps[who.role]} : payload);
    const signature = b64(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, who.pair.privateKey, new TextEncoder().encode(proofMessage(who.uid, who.role, c.body.nonce, purpose, await digest(raw)))));
    return { nonce: c.body.nonce, signature, publicKey: who.publicKey, payload: raw };
  };
  const enroll = async who => { const p = await proof(who, 'register', { familyId: 'family-01', deviceId: 'child-01', version: '2.3.2' }); return p.status ? p : call(who, who.role === 'parent' ? 'registerParent' : 'registerChild', p); };
  return { api, env, storage, documents, sent, call, proof, enroll, caps, failNextWrite() { failWrite=true; }, offline(v) { offline = v; }, crashAfterCommit() { crash = true; }, advance(ms) { now += ms; }, restart() { registry = new FamilyRegistry({ storage }, env, api, () => now); }, otherInstance() { const other = new FamilyRegistry({ storage: new Storage() }, env, api, () => now); return async (who, operation, body) => { const r = await other.fetch(new Request('https://internal', { method: 'POST', body: JSON.stringify({ uid: who.uid, operation, body }) })); return { status: r.status, body: await r.json() }; }; } };
}
export const tokenPayload = (token, generation) => ({ familyId: 'family-01', deviceId: 'child-01', token, generation });
export const wakePayload = () => ({ deviceId: 'child-01', requestId: String(NOW), requestedAt: NOW });
