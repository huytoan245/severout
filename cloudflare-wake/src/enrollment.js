import { ApiError, GoogleApi, b64, unb64 } from './google.js';
import { validateRequest } from './coordinator.js';
import { bindingHash, canonicalHash, constantTimeEqual } from './device-binding.js';
const utf8 = new TextEncoder();
const roles = ['parent', 'child'];
const purposes = ['register', 'rebind', 'token', 'wake'];
export const proofMessage = (uid, role, nonce, purpose, hash) => `FL232\nfamily-01\nchild-01\n${role}\n${uid}\n${nonce}\n${purpose}\n${hash}`;
export const digest = async text => b64(await crypto.subtle.digest('SHA-256', utf8.encode(text)));
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k));
const json = (body, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
export function familyState(f) { return { familyId: 'family-01', deviceId: 'child-01', parentRegistered: Boolean(f.parentUid), childRegistered: Boolean(f.childUid), paired: Boolean(f.parentUid && f.childUid), epoch: f.epoch || 1 }; }
// Firestore CAS is authoritative, including races with an administrator.
export class FamilyRegistry {
  constructor(ctx, env, api = new GoogleApi(env), clock = Date.now) { this.ctx = ctx; this.env = env; this.api = api; this.clock = clock; this.serial = Promise.resolve(); }
  locked(fn) { const next = this.serial.then(fn); this.serial = next.catch(() => {}); return next; }
  async rate(uid) {
    const now = this.clock(), key = 'rate:' + await digest(uid);
    await this.ctx.storage.transaction(async tx => {
      const per = await tx.get(key) || { start: now, count: 0 };
      const global = await tx.get('global-rate') || { start: now, count: 0 };
      for (const r of [per, global]) if (now - r.start >= 60000) { r.start = now; r.count = 0; }
      if (++per.count > 32 || ++global.count > 96) throw new ApiError('rate_limited', 429);
      await tx.put(key, per); await tx.put('global-rate', global);
      const alarm = await tx.getAlarm();
      if (alarm == null || alarm > now + 180000) await tx.setAlarm(now + 180000);
    });
  }
  async alarm() {
    return this.locked(async () => {
      const now = this.clock();
      const nonces = await this.ctx.storage.list({ prefix: 'nonce:', limit: 512 });
      const rates = await this.ctx.storage.list({ prefix: 'rate:', limit: 512 });
      for (const [key, value] of nonces) if (value.expires < now) await this.ctx.storage.delete(key);
      for (const [key, value] of rates) if (value.start + 120000 < now) await this.ctx.storage.delete(key);
      if (nonces.size || rates.size) await this.ctx.storage.setAlarm(now + 180000);
    });
  }
  async family() {
    const f = await this.api.readFamily();
    if (f.updateTime && (f.schemaVersion !== 232 || f.familyId !== 'family-01' || f.childDeviceId !== 'child-01' || !Number.isSafeInteger(f.epoch) || f.epoch < 1 || (f.parentUid && f.parentUid === f.childUid))) throw new ApiError('invalid_family_state');
    return f;
  }
  async fetch(request) {
    return this.locked(async () => {
      try {
        const { uid, operation, body } = await request.json();
        if (typeof uid !== 'string' || !uid || uid.length > 128) throw new ApiError('unauthorized', 401);
        await this.rate(uid);
        if (operation === 'state' || operation === 'diagnostics') {
          const f = await this.family();
          if (uid !== f.parentUid && uid !== f.childUid) throw new ApiError('role_not_registered', 403);
          return json({ ...familyState(f), role: uid === f.parentUid ? 'parent' : 'child' });
        }
        if (operation === 'challenge') {
          if (!exact(body, ['familyId', 'deviceId', 'role', 'purpose']) || body.familyId !== 'family-01' || body.deviceId !== 'child-01' || !roles.includes(body.role) || !purposes.includes(body.purpose) || (body.purpose === 'wake' && body.role !== 'parent') || (body.purpose === 'token' && body.role !== 'child')) throw new ApiError('invalid_request', 400);
          const f = await this.family(), owner = f[body.role + 'Uid'];
          if ((f.retiredUidHashes || '').split(',').includes(await digest(uid))) throw new ApiError('retired_identity', 403);
          if (body.purpose === 'rebind') {
            if (!owner || !canonicalHash(f[body.role+'DeviceBindingHash'])) throw new ApiError('operator_recovery_required', 403);
            // A fresh UID is necessary to revoke the old Firestore JWT as well
            // as the old installation key. Rules cannot verify P-256 proofs.
            if (owner === uid) throw new ApiError('fresh_identity_required', 403);
            if (!canonicalHash(this.env.DEVICE_BINDING_PEPPER)) throw new ApiError('binding_not_configured');
          } else if (owner && owner !== uid) throw new ApiError('slot_occupied', 403);
          if (uid === f[(body.role === 'parent' ? 'child' : 'parent') + 'Uid']) throw new ApiError('role_conflict', 403);
          if (!['register','rebind'].includes(body.purpose) && owner !== uid) throw new ApiError('role_not_registered', 403);
          if (!owner) {
            if (this.env.ENROLLMENT_ENABLED !== 'true') throw new ApiError('enrollment_closed', 403);
            this.bootstrapAvailable(f, body.role);
          }
          const nonce = b64(crypto.getRandomValues(new Uint8Array(32))), expires = this.clock() + 120000;
          await this.ctx.storage.put('nonce:' + nonce, { uid, role: body.role, purpose: body.purpose, expires, epoch: f.epoch || 1, generation: f[body.role+'RebindGeneration'] || 0 });
          return json({ nonce, expires, epoch:f.epoch, needsBootstrap: body.purpose === 'register' && !owner });
        }
        const role = ['registerParent','rebindParent','wake'].includes(operation) ? 'parent' : 'child';
        const purpose = operation.startsWith('register') ? 'register' : operation.startsWith('rebind') ? 'rebind' : operation;
        if (!['registerParent', 'registerChild', 'rebindParent', 'rebindChild', 'token', 'wake'].includes(operation) || !exact(body, ['nonce', 'signature', 'publicKey', 'payload']) || typeof body.nonce !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(body.nonce) || typeof body.payload !== 'string' || utf8.encode(body.payload).length > 3072 || typeof body.publicKey !== 'string' || !/^[A-Za-z0-9_-]{120,160}$/.test(body.publicKey) || typeof body.signature !== 'string' || !/^[A-Za-z0-9_-]{86}$/.test(body.signature)) throw new ApiError('invalid_proof', 400);
        const key = 'nonce:' + body.nonce, challenge = await this.ctx.storage.get(key);
        if (!challenge || challenge.expires <= this.clock() || challenge.uid !== uid || challenge.role !== role || challenge.purpose !== purpose) throw new ApiError('invalid_nonce', 403);
        const hash = await digest(JSON.stringify(body));
        if (challenge.proofHash && challenge.proofHash !== hash) throw new ApiError('nonce_reused', 409);
        let payload;
        try {
          payload = JSON.parse(body.payload);
          const publicKey = await crypto.subtle.importKey('spki', unb64(body.publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
          if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, unb64(body.signature), utf8.encode(proofMessage(uid, role, body.nonce, purpose, await digest(body.payload))))) throw new Error();
        } catch { throw new ApiError('invalid_signature', 403); }
        const f = await this.family();
        if ((f.retiredUidHashes || '').split(',').includes(await digest(uid))) throw new ApiError('retired_identity', 403);
        if (purpose === 'rebind' && f[role+'RebindProofHash'] === hash && f[role+'Uid'] === uid && f[role+'Key'] === body.publicKey && f[role+'RebindGeneration'] === challenge.generation + 1) {
          return json({ ...familyState(f), registered:true, rebound:true, role });
        }
        if ((f.epoch || 1) !== challenge.epoch) throw new ApiError('family_changed', 403);
        if (purpose !== 'rebind' && f[role + 'Uid'] && (f[role + 'Uid'] !== uid || f[role + 'Key'] !== body.publicKey)) throw new ApiError('slot_occupied', 403);
        if (!['register','rebind'].includes(purpose) && (!f[role + 'Uid'] || f[role + 'Key'] !== body.publicKey)) throw new ApiError('role_not_registered', 403);
        let deviceBinding;
        if (purpose === 'register') {
          if (!(exact(payload, ['familyId', 'deviceId', 'version', 'deviceRecoveryMaterial']) || exact(payload, ['familyId', 'deviceId', 'version', 'deviceRecoveryMaterial', 'bootstrap'])) || payload.familyId !== 'family-01' || payload.deviceId !== 'child-01' || payload.version !== '2.3.2' || !canonicalHash(payload.deviceRecoveryMaterial)) throw new ApiError('invalid_request', 400);
          if (f[role + 'Uid']) {
            // Only the identical committed claim can retry a consumed capability.
            // A fresh proof for the existing UID/key must omit bootstrap entirely.
            if (Object.hasOwn(payload, 'bootstrap') && f[role + 'ClaimProofHash'] !== hash) throw new ApiError('bootstrap_consumed', 403);
          } else {
            this.bootstrapAvailable(f, role);
            if (typeof payload.bootstrap !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(payload.bootstrap) || b64(unb64(payload.bootstrap)) !== payload.bootstrap || await digest(payload.bootstrap) !== f[role + 'BootstrapHash']) throw new ApiError('invalid_bootstrap', 403);
            deviceBinding = await bindingHash(this.env.DEVICE_BINDING_PEPPER, role, payload.deviceRecoveryMaterial);
          }
        } else if (purpose === 'rebind') {
          if (!exact(payload,['familyId','deviceId','version','deviceRecoveryMaterial']) || payload.familyId !== 'family-01' || payload.deviceId !== 'child-01' || payload.version !== '2.3.2') throw new ApiError('invalid_request',400);
          if (!f[role+'Uid'] || !canonicalHash(f[role+'DeviceBindingHash'])) throw new ApiError('operator_recovery_required',403);
          deviceBinding = await bindingHash(this.env.DEVICE_BINDING_PEPPER, role, payload.deviceRecoveryMaterial);
          if (!constantTimeEqual(deviceBinding,f[role+'DeviceBindingHash'])) throw new ApiError('operator_recovery_required',403);
          if (uid === f[(role === 'parent' ? 'child' : 'parent')+'Uid']) throw new ApiError('role_conflict',403);
          if (f[role+'Uid'] === uid) throw new ApiError('fresh_identity_required',403);
          if (f[role+'Uid'] === uid && f[role+'Key'] === body.publicKey) throw new ApiError('already_registered',409);
          if (!Number.isSafeInteger(challenge.generation) || challenge.generation < 0 || challenge.generation >= Number.MAX_SAFE_INTEGER || (f[role+'RebindGeneration'] || 0) !== challenge.generation || f.epoch >= Number.MAX_SAFE_INTEGER) throw new ApiError('family_changed',403);
        } else if (purpose === 'token') {
          if (!exact(payload, ['familyId', 'deviceId', 'token', 'generation']) || payload.familyId !== 'family-01' || payload.deviceId !== 'child-01' || typeof payload.token !== 'string' || payload.token.length < 10 || payload.token.length > 2048 || !/^[A-Za-z0-9_:\-.]+$/.test(payload.token) || !Number.isSafeInteger(payload.generation) || payload.generation < 1) throw new ApiError('invalid_request', 400);
        } else validateRequest(payload, this.clock());
        if (challenge.result) return json(challenge.result);
        challenge.proofHash = hash; await this.ctx.storage.put(key, challenge);
        let result;
        if (purpose === 'register') {
          if (uid === f[(role === 'parent' ? 'child' : 'parent') + 'Uid']) throw new ApiError('role_conflict', 403);
          if (!f[role + 'Uid']) {
            if (this.env.ENROLLMENT_ENABLED !== 'true') throw new ApiError('enrollment_closed', 403);
            // One conditional Firestore document write claims AND consumes. No
            // plaintext capability is persisted in Firestore or durable storage.
            const next = { ...f, familyId: 'family-01', childDeviceId: 'child-01', epoch: f.epoch || 1, [role + 'Uid']: uid, [role + 'Key']: body.publicKey, [role + 'DeviceBindingHash']: deviceBinding, [role+'RebindGeneration']:f[role+'RebindGeneration'] || 0, [role + 'RegisteredAt']: this.clock(), [role + 'BootstrapConsumed']: true, [role + 'BootstrapConsumedAt']: this.clock(), [role + 'ClaimProofHash']: hash };
            next.locked = Boolean(next.parentUid && next.childUid);
            if (!await this.api.writeFamily(f, next)) throw new ApiError('registration_raced', 409, true);
            result = { ...familyState(next), registered: true, role };
          } else result = { ...familyState(f), registered: true, role };
        } else if (purpose === 'rebind') {
          const retired = new Set((f.retiredUidHashes || '').split(',').filter(Boolean));
          retired.add(await digest(f[role+'Uid']));
          const next = {...f,epoch:f.epoch+1,[role+'Uid']:uid,[role+'Key']:body.publicKey,[role+'RebindGeneration']:challenge.generation+1,[role+'ReboundAt']:this.clock(),[role+'RebindProofHash']:hash,retiredUidHashes:[...retired].join(',')};
          if (!await this.api.rebindIdentity(f,next,role,{oldUidHash:await digest(f[role+'Uid']),oldKeyHash:await digest(f[role+'Key']),newUidHash:await digest(uid),proofHash:hash,time:this.clock()})) throw new ApiError('rebind_raced',409,true);
          result = {...familyState(next),registered:true,rebound:true,role};
        } else if (purpose === 'token') result = await this.api.updateChildToken(f, uid, payload, this.clock());
        else {
          if (!f.childUid) throw new ApiError('child_not_registered', 409, true);
          const stub = this.env.WAKE_STATE.get(this.env.WAKE_STATE.idFromName('child-01'));
          const response = await stub.fetch(new Request('https://wake.internal/v1/wake', { method: 'POST', body: JSON.stringify({ command: payload, binding: { parentUid: uid, epoch: f.epoch } }) }));
          const responseBody = await response.json();
          if (!response.ok) return json(responseBody, response.status);
          result = responseBody;
        }
        challenge.result = result; await this.ctx.storage.put(key, challenge);
        return json(result);
      } catch (e) { return json({ error: e instanceof ApiError ? e.code : 'backend_unavailable', ...(Number.isSafeInteger(e.requiredGeneration) ? { requiredGeneration: e.requiredGeneration } : {}) }, e instanceof ApiError ? e.status : 503); }
    });
  }
  bootstrapAvailable(f, role) {
    if (!f.updateTime || !/^[A-Za-z0-9_-]{43}$/.test(f[role + 'BootstrapHash'] || '') || (f.retiredBootstrapHashes || '').split(',').includes(f[role + 'BootstrapHash']) || f.parentBootstrapHash === f.childBootstrapHash || f[role + 'BootstrapConsumed'] !== false) throw new ApiError('bootstrap_unavailable', 403);
    if (f[role+'BootstrapRevoked'] === true) throw new ApiError('bootstrap_revoked',403);
    if (f[role+'BootstrapMode'] === 'RECOVERY_EXPIRING') {
      if (!Number.isSafeInteger(f[role+'BootstrapExpiresAt']) || f[role+'BootstrapExpiresAt'] <= this.clock()) throw new ApiError('bootstrap_expired',403);
    } else if (f.bootstrapMode !== 'UNTIL_CONSUMED_OR_REVOKED' || Object.hasOwn(f,role+'BootstrapExpiresAt')) throw new ApiError('bootstrap_unavailable',403);
  }
}
