const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const CHILD_DOC_PATH = "devices/child-01";
const WAKE_TTL_MS = 15 * 60 * 1000;

function numberField(obj, key) {
  const value = Number(obj?.[key] || 0);
  return Number.isFinite(value) ? value : 0;
}

function tokenField(obj) {
  return typeof obj?.fcmToken === "string" ? obj.fcmToken.trim() : "";
}

function permanentTokenError(code) {
  return code === "messaging/registration-token-not-registered" ||
    code === "messaging/invalid-registration-token";
}

exports.wakeChildOnRefresh = onDocumentUpdated(
  {
    document: CHILD_DOC_PATH,
    region: "asia-southeast1",
    retry: true,
  },
  async (event) => {
    const before = event.data?.before?.data() || {};
    const after = event.data?.after?.data() || {};

    const beforeRefresh = numberField(before, "refreshRequestedAt");
    const refreshRequestedAt = numberField(after, "refreshRequestedAt");
    const beforeToken = tokenField(before);
    const afterToken = tokenField(after);
    const refreshChanged = refreshRequestedAt > beforeRefresh;
    const tokenBecameAvailable = afterToken && afterToken !== beforeToken;

    // A late token publication must also wake a still-pending refresh. This
    // closes the install/offline race where Parent requested a location before
    // the Child's FCM token had successfully reached Firestore.
    if (!refreshChanged && !tokenBecameAvailable) return;
    if (refreshRequestedAt <= 0) return;

    const db = getFirestore();
    const ref = db.doc(CHILD_DOC_PATH);
    const currentSnap = await ref.get();
    const current = currentSnap.data() || after;
    const currentRefresh = numberField(current, "refreshRequestedAt");
    const completedFor = numberField(current, "refreshCompletedFor");
    const failedFor = numberField(current, "refreshFailedFor");
    const alreadySentFor = numberField(current, "wakeDispatchFor");
    const alreadySentResult = String(current.wakeDispatchResult || "");
    const token = tokenField(current);

    if (currentRefresh <= 0 || completedFor >= currentRefresh || failedFor >= currentRefresh) return;
    if (alreadySentFor === currentRefresh && alreadySentResult === "sent") return;

    const now = Date.now();
    if (!token) {
      await ref.set(
        {
          wakeDispatchFor: currentRefresh,
          wakeDispatchAt: now,
          wakeDispatchResult: "missing_fcm_token",
        },
        { merge: true }
      );
      return;
    }

    try {
      const messageId = await getMessaging().send({
        token,
        data: {
          type: "location_refresh",
          requestId: String(currentRefresh),
        },
        android: {
          priority: "high",
          ttl: WAKE_TTL_MS,
        },
      });

      await ref.set(
        {
          wakeDispatchFor: currentRefresh,
          wakeDispatchAt: now,
          wakeDispatchResult: "sent",
          wakeDispatchMessageId: messageId,
          wakeDispatchProtocol: "v229",
        },
        { merge: true }
      );
    } catch (error) {
      const code = error && typeof error.code === "string" ? error.code : "unknown";
      await ref.set(
        {
          wakeDispatchFor: currentRefresh,
          wakeDispatchAt: now,
          wakeDispatchResult: `error:${code}`,
          wakeDispatchProtocol: "v229",
        },
        { merge: true }
      );

      if (permanentTokenError(code)) {
        await ref.set(
          {
            fcmToken: null,
            fcmTokenInvalidatedAt: Date.now(),
          },
          { merge: true }
        );
        return;
      }

      // retry:true only retries when the function fails. Throw transient errors
      // after recording diagnostics; requestId dedupe on Child prevents duplicate
      // refresh execution if a retry races a previous successful send.
      throw error;
    }
  }
);
