const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const CHILD_DOC_PATH = "devices/child-01";

exports.wakeChildOnRefresh = onDocumentUpdated(
  {
    document: CHILD_DOC_PATH,
    region: "asia-southeast1",
    retry: false,
  },
  async (event) => {
    const before = event.data?.before?.data() || {};
    const after = event.data?.after?.data() || {};

    const beforeRefresh = Number(before.refreshRequestedAt || 0);
    const refreshRequestedAt = Number(after.refreshRequestedAt || 0);
    if (!Number.isFinite(refreshRequestedAt) || refreshRequestedAt <= beforeRefresh) {
      return;
    }

    const token = typeof after.fcmToken === "string" ? after.fcmToken.trim() : "";
    const ref = getFirestore().doc(CHILD_DOC_PATH);
    const now = Date.now();

    if (!token) {
      await ref.set(
        {
          wakeDispatchFor: refreshRequestedAt,
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
          requestId: String(refreshRequestedAt),
        },
        android: {
          priority: "high",
          ttl: 60 * 1000,
        },
      });

      await ref.set(
        {
          wakeDispatchFor: refreshRequestedAt,
          wakeDispatchAt: now,
          wakeDispatchResult: "sent",
          wakeDispatchMessageId: messageId,
        },
        { merge: true }
      );
    } catch (error) {
      const code = error && typeof error.code === "string" ? error.code : "unknown";
      await ref.set(
        {
          wakeDispatchFor: refreshRequestedAt,
          wakeDispatchAt: now,
          wakeDispatchResult: `error:${code}`,
        },
        { merge: true }
      );

      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        await ref.set(
          {
            fcmToken: null,
            fcmTokenInvalidatedAt: Date.now(),
          },
          { merge: true }
        );
      }
    }
  }
);
