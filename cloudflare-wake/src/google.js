const PROJECT = 'family-location-884e5';
const JWKS = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const encoder = new TextEncoder();
export class ApiError extends Error {
  constructor(code, status = 503, retry = false) { super(code); this.code = code; this.status = status; this.retry = retry; }
}
export const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
export const unb64 = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - s.length % 4) % 4)), c => c.charCodeAt(0));
export async function boundedFetch(url, options = {}, fetcher = fetch) {
  try { const response = await fetcher(url, { ...options, redirect: 'manual', signal: AbortSignal.timeout(8000) }); if (response.status >= 300 && response.status < 400) throw new Error('redirect_rejected'); return response; }
  catch { throw new ApiError('upstream_unavailable', 503, true); }
}
export class FirebaseVerifier {
  constructor(fetcher = fetch) { this.fetcher = fetcher; this.keys = null; this.until = 0; this.flight = null; this.lastFetch = 0; }
  async load(now) {
    if (!this.flight) this.flight = (async () => {
      const response = await boundedFetch(JWKS, {}, this.fetcher);
      if (!response.ok) throw new ApiError('auth_keys_unavailable', 503, true);
      const body = await response.json();
      if (!Array.isArray(body.keys) || !body.keys.length) throw new ApiError('auth_keys_unavailable');
      this.keys = body.keys; this.lastFetch = now;
      const ttl = Number(/max-age=(\d+)/.exec(response.headers.get('cache-control') || '')?.[1] || 300);
      this.until = now + Math.min(Math.max(ttl, 30), 21600) * 1000;
    })().finally(() => { this.flight = null; });
    await this.flight;
  }
  async verify(token, uid, now = Date.now()) {
    if (typeof token !== 'string' || token.length > 8192) throw new ApiError('unauthorized', 401);
    try {
      const parts = token.split('.'); if (parts.length !== 3) throw new Error();
      const h = JSON.parse(new TextDecoder().decode(unb64(parts[0])));
      const p = JSON.parse(new TextDecoder().decode(unb64(parts[1])));
      const sec = now / 1000;
      if (h.alg !== 'RS256' || typeof h.kid !== 'string' || h.crit || p.aud !== PROJECT || p.iss !== `https://securetoken.google.com/${PROJECT}` || (uid != null && p.sub !== uid) || typeof p.sub !== 'string' || !p.sub || p.sub.length > 128 || !Number.isFinite(p.exp) || p.exp <= sec || !Number.isFinite(p.iat) || p.iat > sec + 30 || !Number.isFinite(p.auth_time) || p.auth_time > sec + 30 || p.auth_time < 0 || p.iat < 0 || p.exp <= p.iat || p.exp - p.iat > 3660 || p.auth_time > p.iat + 30 || (p.nbf != null && p.nbf > sec + 30)) throw new Error();
      if (!this.keys || now >= this.until) await this.load(now);
      let jwk = this.keys.find(k => k.kid === h.kid && k.kty === 'RSA');
      if (!jwk && now - this.lastFetch >= 60000) { await this.load(now); jwk = this.keys.find(k => k.kid === h.kid && k.kty === 'RSA'); }
      if (!jwk) throw new Error();
      const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
      if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, unb64(parts[2]), encoder.encode(parts[0] + '.' + parts[1]))) throw new Error();
      return p.sub;
    } catch (e) { if (e instanceof ApiError && e.status === 503) throw e; throw new ApiError('unauthorized', 401); }
  }
}
export class GoogleApi {
  constructor(env, fetcher = fetch) { this.env = env; this.fetcher = fetcher; this.cached = null; this.flight = null; }
  async accessToken() {
    if (this.cached && this.cached.until > Date.now() + 60000) return this.cached.token;
    if (!this.flight) this.flight = (async () => {
      let sa; try { sa = JSON.parse(this.env.GOOGLE_SERVICE_ACCOUNT_JSON); } catch { throw new ApiError('server_not_configured'); }
      if (sa.project_id !== PROJECT || typeof sa.client_email !== 'string' || !sa.client_email.endsWith('.iam.gserviceaccount.com') || typeof sa.private_key !== 'string') throw new ApiError('server_not_configured');
      const now = Math.floor(Date.now() / 1000);
      const header = b64(encoder.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
      const payload = b64(encoder.encode(JSON.stringify({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })));
      const der = unb64(sa.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, ''));
      const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
      const signed = header + '.' + payload;
      const jwt = signed + '.' + b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(signed)));
      const r = await boundedFetch('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }) }, this.fetcher);
      if (!r.ok) throw new ApiError('server_oauth_failed', r.status === 400 || r.status === 403 ? 503 : r.status, r.status >= 500 || r.status === 429);
      const d = await r.json(); if (typeof d.access_token !== 'string' || !Number.isFinite(d.expires_in)) throw new ApiError('server_oauth_failed');
      this.cached = { token: d.access_token, until: Date.now() + d.expires_in * 1000 }; return d.access_token;
    })().finally(() => { this.flight = null; });
    return this.flight;
  }
  async call(url, options = {}) {
    const token = await this.accessToken();
    const r = await boundedFetch(url, { ...options, headers: { ...options.headers, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } }, this.fetcher);
    if (r.status === 401) this.cached = null;
    return r;
  }
  docUrl() { return `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/devices/child-01`; }
  familyUrl() { return this.docUrl().replace('devices/child-01', 'families/family-01'); }
  async readDocument(url) {
    const r = await this.call(url);
    if (r.status === 404) return {};
    if (!r.ok) throw new ApiError('state_read_failed', 503, true);
    const d = await r.json(); const out = {};
    for (const [k, v] of Object.entries(d.fields || {})) out[k] = 'integerValue' in v ? Number(v.integerValue) : 'booleanValue' in v ? v.booleanValue : v.stringValue;
    return { ...out, updateTime: d.updateTime };
  }
  async readFamily() { return this.readDocument(this.familyUrl()); }
  fields(values) {
    return Object.fromEntries(Object.entries(values).filter(([k]) => k !== 'updateTime').map(([k, v]) => [k, typeof v === 'number' ? { integerValue: String(v) } : typeof v === 'boolean' ? { booleanValue: v } : { stringValue: String(v) }]));
  }
  async conflict(response) {
    if ([409, 412].includes(response.status)) return true;
    if (response.status !== 400) return false;
    try { return ['FAILED_PRECONDITION', 'ABORTED'].includes((await response.clone().json()).error?.status); } catch { return false; }
  }
  async writeFamily(previous, values) {
    const query = new URLSearchParams(previous.updateTime ? { 'currentDocument.updateTime': previous.updateTime } : { 'currentDocument.exists': 'false' });
    const r = await this.call(this.familyUrl() + '?' + query, { method: 'PATCH', body: JSON.stringify({ fields: this.fields(values) }) });
    if (await this.conflict(r)) return false;
    if (!r.ok) throw new ApiError('registration_write_failed', 503, true);
    return true;
  }
  async updateChildToken(family, uid, payload, now) {
    if (!family.updateTime || uid !== family.childUid) throw new ApiError('role_not_registered', 403);
    const d = await this.readDocument(this.docUrl());
    const old = d.fcmTokenGeneration || 0;
    if (payload.generation < old || (payload.generation === old && (d.fcmToken !== payload.token || d.fcmTokenOwnerUid !== uid))) {
      const e = new ApiError('token_revision_conflict', 409); e.requiredGeneration = old + 1; throw e;
    }
    if (payload.generation === old) return { generation: old, confirmed: true };
    const values = { fcmToken: payload.token, fcmTokenOwnerUid: uid, fcmTokenGeneration: payload.generation, fcmTokenUpdatedAt: now, fcmTokenVersion: '2.3.1', wakeProtocolVersion: 'v231-device-key' };
    const writes = [
      // A conditional no-op epoch write makes membership and token CAS one
      // atomic commit using only documented Firestore REST Write operations.
      { update: { name: this.familyUrl().split('/v1/')[1], fields: this.fields({ epoch: family.epoch }) }, updateMask: { fieldPaths: ['epoch'] }, currentDocument: { updateTime: family.updateTime } },
      { update: { name: this.docUrl().split('/v1/')[1], fields: this.fields(values) }, updateMask: { fieldPaths: Object.keys(values) }, currentDocument: d.updateTime ? { updateTime: d.updateTime } : { exists: false } }
    ];
    const r = await this.call(this.docUrl().replace('/documents/devices/child-01', '/documents:commit'), { method: 'POST', body: JSON.stringify({ writes }) });
    if (await this.conflict(r)) throw new ApiError('state_changed', 409, true);
    if (!r.ok) throw new ApiError('token_write_failed', 503, true);
    return { generation: payload.generation, confirmed: true };
  }
  async readDevice() {
    const r = await this.call(this.docUrl());
    if (!r.ok) throw new ApiError('device_read_failed', 503, r.status >= 500 || r.status === 429 || r.status === 401);
    const d = await r.json(); const out = {};
    for (const [k, v] of Object.entries(d.fields || {})) out[k] = 'integerValue' in v ? Number(v.integerValue) : 'booleanValue' in v ? v.booleanValue : v.stringValue;
    return { ...out, updateTime: d.updateTime };
  }
  async patchIfCurrent(id, values) {
    const d = await this.readDevice(); if (d.refreshRequestedAt !== id || !d.updateTime) return false;
    const query = new URLSearchParams({ 'currentDocument.updateTime': d.updateTime });
    const fields = {};
    for (const [k, v] of Object.entries(values)) { query.append('updateMask.fieldPaths', k); fields[k] = typeof v === 'number' ? { integerValue: String(v) } : { stringValue: String(v) }; }
    const r = await this.call(this.docUrl() + '?' + query, { method: 'PATCH', body: JSON.stringify({ fields }) });
    if (r.status === 409 || r.status === 412) return false;
    if (!r.ok) throw new ApiError('diagnostic_write_failed', 503, r.status >= 500 || r.status === 429 || r.status === 401);
    return true;
  }
  async send(token, id, ttlMs) {
    const r = await this.call(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, { method: 'POST', body: JSON.stringify({ message: { token, android: { priority: 'HIGH', ttl: `${Math.max(1, Math.floor(ttlMs / 1000))}s`, collapse_key: 'family-location-refresh' }, data: { type: 'location_refresh', deviceId: 'child-01', requestId: String(id), requestedAt: String(id), expiresAt: String(id + 15 * 60000) } } }) });
    if (!r.ok) {
      let d; try { d = await r.json(); } catch { d = {}; }
      const invalid = d.error?.details?.some(x => x['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError' && x.errorCode === 'UNREGISTERED');
      throw new ApiError(invalid ? 'invalid_fcm_token' : 'fcm_send_failed', 503, !invalid && (r.status >= 500 || r.status === 429 || r.status === 401));
    }
    const d = await r.json(); if (typeof d.name !== 'string') throw new ApiError('fcm_response_invalid', 503, true); return d.name;
  }
}
