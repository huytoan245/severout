const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { createWakeHandler } = require("./wake-handler");
initializeApp();
exports.wakeChildOnRefresh = onDocumentUpdated(
  { document: "devices/child-01", region: "asia-southeast1", retry: true },
  createWakeHandler({ db: getFirestore(), messaging: getMessaging() })
);
