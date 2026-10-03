const { createHash, randomUUID } = require("node:crypto");
const WAKE_TTL_MS = 15 * 60 * 1000;
const LEASE_MS = 60 * 1000;
const n = (o, key) => Number.isFinite(Number(o?.[key])) ? Number(o[key]) : 0;
const tokenOf = o => typeof o?.fcmToken === "string" ? o.fcmToken.trim() : "";
const hash = token => createHash("sha256").update(token).digest("hex");
const permanent = code => ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(code);

/** Event delivery is at least once and unordered; FCM send is never exactly once. */
function createWakeHandler({ db, messaging, now = Date.now, ownerId = randomUUID }) {
  return async event => {
    const before = event.data?.before?.data() || {};
    const after = event.data?.after?.data() || {};
    const tokenBecameAvailable = tokenOf(after) && tokenOf(after) !== tokenOf(before);
    if (n(after, "refreshRequestedAt") <= n(before, "refreshRequestedAt") && !tokenBecameAvailable) return;
    const ref = db.doc("devices/child-01");
    const owner = ownerId();
    const claim = await db.runTransaction(async tx => {
      const current = (await tx.get(ref)).data() || {};
      const request = n(current, "refreshRequestedAt");
      if (request <= 0 || n(current, "refreshCompletedFor") >= request || n(current, "refreshFailedFor") >= request) return null;
      const expires = Math.min(n(current, "refreshExpiresAt") || request + WAKE_TTL_MS, request + WAKE_TTL_MS);
      if (now() >= expires || request > now() + 60_000) {
        tx.set(ref, { wakeDispatchFor: request, wakeDispatchAt: now(), wakeDispatchResult: "expired", wakeDispatchProtocol: "v2210" }, { merge: true });
        return null;
      }
      const token = tokenOf(current);
      if (!token) {
        tx.set(ref, { wakeDispatchFor: request, wakeDispatchAt: now(), wakeDispatchResult: "missing_fcm_token", wakeDispatchProtocol: "v2210" }, { merge: true });
        return null;
      }
      const tokenHash = hash(token);
      if (n(current, "wakeDispatchFor") === request && current.wakeDispatchResult === "sent" && current.wakeDispatchTokenHash === tokenHash) return null;
      if (n(current, "wakeLeaseFor") === request && n(current, "wakeLeaseUntil") > now()) return { busy: true };
      tx.set(ref, { wakeLeaseFor: request, wakeLeaseUntil: now() + LEASE_MS, wakeLeaseOwner: owner }, { merge: true });
      return { request, token, tokenHash, expires };
    });
    if (!claim) return;
    if (claim.busy) throw new Error("wake lease busy"); // retry if owner dies

    async function finish(result, messageId, invalidate = false) {
      return db.runTransaction(async tx => {
        const current = (await tx.get(ref)).data() || {};
        if (current.wakeLeaseOwner !== owner || n(current, "wakeLeaseFor") !== claim.request) return;
        const fields = { wakeLeaseUntil: 0, wakeLeaseOwner: "" };
        if (n(current, "refreshRequestedAt") === claim.request) Object.assign(fields, {
          wakeDispatchFor: claim.request, wakeDispatchAt: now(), wakeDispatchResult: result,
          wakeDispatchProtocol: "v2210", wakeDispatchTokenHash: claim.tokenHash,
          ...(messageId ? { wakeDispatchMessageId: messageId } : {})
        });
        if (invalidate && tokenOf(current) === claim.token) Object.assign(fields, { fcmToken: null, fcmTokenInvalidatedAt: now() });
        tx.set(ref, fields, { merge: true });
      });
    }
    try {
      const current = (await ref.get()).data() || {};
      if (n(current, "refreshRequestedAt") !== claim.request || n(current, "refreshCompletedFor") >= claim.request || n(current, "refreshFailedFor") >= claim.request) {
        await finish("superseded"); return;
      }
      if (tokenOf(current) !== claim.token) { await finish("token_rotated_retry"); throw new Error("token changed during dispatch"); }
      const remaining = claim.expires - now();
      if (remaining <= 0) { await finish("expired"); return; }
      const messageId = await messaging.send({ token: claim.token, data: { type: "location_refresh", requestId: String(claim.request), expiresAt: String(claim.expires) }, android: { priority: "high", ttl: remaining } });
      await finish("sent", messageId);
    } catch (error) {
      const code = typeof error?.code === "string" ? error.code : "unknown";
      await finish(`error:${code}`, null, permanent(code));
      if (!permanent(code)) throw error;
    }
  };
}
module.exports = { createWakeHandler, WAKE_TTL_MS };
