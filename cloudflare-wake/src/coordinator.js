import { ApiError, GoogleApi } from './google.js';
export const TTL = 15 * 60000;
export function validateRequest(b, now = Date.now()) {
  if (!b || Object.keys(b).some(k => !['deviceId', 'requestId', 'requestedAt'].includes(k)) || b.deviceId !== 'child-01' || !Number.isSafeInteger(b.requestedAt) || b.requestId !== String(b.requestedAt) || b.requestedAt <= 0) throw new ApiError('invalid_request', 400);
  if (b.requestedAt > now + 30000 || now >= b.requestedAt + TTL) throw new ApiError('expired_request', 410);
  return b.requestedAt;
}
export class WakeCoordinator {
  constructor(ctx, env, api = new GoogleApi(env), clock = Date.now) { this.ctx = ctx; this.env = env; this.api = api; this.clock = clock; this.serial = Promise.resolve(); }
  locked(fn) { const next = this.serial.then(fn); this.serial = next.catch(() => {}); return next; }
  async fetch(request) {
    return this.locked(async () => {
      try {
        const b = await request.json(); const id = validateRequest(b.command, this.clock());
        const family = await this.api.readFamily();
        if (!family.parentUid || !family.childUid || b.binding?.parentUid !== family.parentUid || b.binding?.epoch !== family.epoch) throw new ApiError('family_changed', 403);
        let state;
        await this.ctx.storage.transaction(async tx => {
          const rate = await tx.get('rate') || { from: this.clock(), count: 0 };
          if (this.clock() - rate.from >= 60000) { rate.from = this.clock(); rate.count = 0; }
          if (++rate.count > 12) throw new ApiError('rate_limited', 429);
          await tx.put('rate', rate);
          state = await tx.get('request');
          if (state && state.id > id) throw new ApiError('superseded_request', 409);
          if (!state || state.id < id) {
            state = { id, parentUid: family.parentUid, epoch: family.epoch, expires: id + TTL, status: 'accepted', attempts: 0, sentFingerprint: '', nextAt: this.clock() };
            await tx.put('request', state); await tx.setAlarm(this.clock() + 1000);
          }
        });
        if (['completed', 'received', 'expired', 'failed', 'sent_unconfirmed'].includes(state.status)) return this.response(state);
        if (state.nextAt <= this.clock()) {
          // Return durable acceptance promptly. Alarm owns recovery if this
          // asynchronous attempt is interrupted after the HTTP reply.
          this.ctx.waitUntil(this.locked(async () => { const current = await this.ctx.storage.get('request'); if (current?.id === id) await this.attempt(current); }));
        }
        return this.response(state);
      } catch (e) { return Response.json({ error: e instanceof ApiError ? e.code : 'backend_unavailable' }, { status: e instanceof ApiError ? e.status : 503, headers: { 'Cache-Control': 'no-store', ...(e.status === 429 ? { 'Retry-After': '60' } : {}) } }); }
    });
  }
  response(s) { return Response.json({ requestId: String(s.id), status: s.status, retryAt: s.nextAt || 0, ...(s.status === 'failed' ? { error: s.error || 'wake_failed' } : {}) }, { status: s.status === 'failed' ? 502 : 200, headers: { 'Cache-Control': 'no-store' } }); }
  async alarm() { return this.locked(async () => { const s = await this.ctx.storage.get('request'); if (s && !['completed', 'received', 'expired', 'failed', 'sent_unconfirmed'].includes(s.status)) await this.attempt(s); }); }
  async attempt(s) {
    const now = this.clock();
    if (now >= s.expires) { s.status = 'expired'; s.nextAt = 0; await this.ctx.storage.put('request', s); await this.ctx.storage.deleteAlarm(); return; }
    if (now < s.nextAt) { await this.ctx.storage.setAlarm(s.nextAt); return; }
    // Persist a crash recovery alarm before external I/O. A crash after FCM send
    // but before storing 'sent' can duplicate delivery; Child must be idempotent.
    s.attempts++; s.nextAt = now + 30000;
    await this.ctx.storage.transaction(async tx => { await tx.put('request', s); await tx.setAlarm(Math.min(s.nextAt, s.expires)); });
    try {
      const family = await this.api.readFamily();
      if (!family.parentUid || !family.childUid || family.parentUid !== s.parentUid || family.epoch !== s.epoch) throw new ApiError('family_changed', 403);
      const d = await this.api.readDevice();
      if ((family.schemaVersion === 232 && d.refreshEpoch !== s.epoch) || d.refreshRequestedAt !== s.id || d.refreshRequestedBy !== family.parentUid || d.refreshExpiresAt !== s.expires) throw new ApiError('command_mismatch', 409);
      if (d.refreshCompletedFor === s.id || d.refreshFailedFor === s.id) { s.status = 'completed'; await this.finish(s); return; }
      if (d.refreshReceivedFor === s.id) { s.status = 'received'; await this.finish(s); return; }
      if (d.fcmTokenOwnerUid !== family.childUid) throw new ApiError('child_identity_unconfirmed', 503, true);
      await this.api.patchIfCurrent(s.id, { wakeBackendFor: s.id, wakeBackendAt: now, wakeBackendResult: 'accepted' });
      if (!d.fcmToken || typeof d.fcmToken !== 'string') throw new ApiError('missing_fcm_token', 503, true);
      const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(d.fcmToken)));
      const fingerprint = Array.from(hash, x => x.toString(16).padStart(2, '0')).join('');
      if (s.invalidFingerprint === fingerprint) throw new ApiError('waiting_token_rotation', 503, true);
      if (s.sentFingerprint !== fingerprint) {
        // Re-read immediately before send; never send a stale/newer command or
        // a token superseded during the preceding diagnostic write.
        const current = await this.api.readDevice();
        const currentFamily = await this.api.readFamily();
        if (currentFamily.updateTime !== family.updateTime || currentFamily.epoch !== s.epoch || currentFamily.parentUid !== s.parentUid || currentFamily.childUid !== family.childUid) throw new ApiError('family_changed', 403);
        if ((currentFamily.schemaVersion === 232 && current.refreshEpoch !== s.epoch) || current.refreshRequestedAt !== s.id || current.refreshRequestedBy !== family.parentUid || current.fcmToken !== d.fcmToken || current.fcmTokenOwnerUid !== family.childUid) throw new ApiError('state_changed', 503, true);
        if (current.refreshCompletedFor === s.id || current.refreshFailedFor === s.id) { s.status = 'completed'; await this.finish(s); return; }
        if (this.clock() >= s.expires) throw new ApiError('expired_request', 410);
        let messageId;
        try { messageId = await this.api.send(d.fcmToken, s.id, s.expires - this.clock()); }
        catch (e) { if (e instanceof ApiError && e.code === 'invalid_fcm_token') { s.invalidFingerprint = fingerprint; throw new ApiError('waiting_token_rotation', 503, true); } throw e; }
        s.sentFingerprint = fingerprint; s.messageId = messageId;
        // Persist acceptance before optional Firestore diagnostics. A diagnostic
        // failure must retry that write, never repeat the already accepted send.
        s.status = 'sent'; await this.ctx.storage.put('request', s);
      }
      await this.api.patchIfCurrent(s.id, { wakeDispatchFor: s.id, wakeDispatchAt: this.clock(), wakeDispatchResult: 'sent', wakeDispatchMessageId: s.messageId || '' });
      // Bounded receipt/token-rotation checks after FCM accepts a send. There is
      // no Firestore trigger on Spark, so a late rotated token needs this path.
      s.status = s.attempts >= 8 ? 'sent_unconfirmed' : 'sent';
      if (s.status === 'sent_unconfirmed') await this.finish(s);
      else { s.nextAt = Math.min(this.clock() + Math.min(30000 * 2 ** (s.attempts - 1), 120000), s.expires); await this.ctx.storage.transaction(async tx => { await tx.put('request', s); await tx.setAlarm(s.nextAt); }); }
    } catch (e) {
      const retry = !(e instanceof ApiError) || e.retry;
      s.error = e instanceof ApiError ? e.code : 'upstream_unavailable';
      if (retry && s.attempts < 8 && this.clock() < s.expires) {
        s.status = s.sentFingerprint ? 'sent_diagnostics_pending' : 'retrying';
        s.nextAt = Math.min(this.clock() + Math.min(30000 * 2 ** (s.attempts - 1), 120000), s.expires);
        await this.ctx.storage.transaction(async tx => { await tx.put('request', s); await tx.setAlarm(s.nextAt); });
      } else { s.status = s.error === 'expired_request' ? 'expired' : 'failed'; await this.finish(s); }
    }
  }
  async finish(s) { s.nextAt = 0; await this.ctx.storage.transaction(async tx => { await tx.put('request', s); await tx.deleteAlarm(); }); }
}
