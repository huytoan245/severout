import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
const args = process.argv.slice(2);
const value = key => args[args.indexOf(key) + 1];
const parent = value('--parent-uid'), child = value('--child-uid'), output = value('--output');
if (!args.includes('--parent-uid') || !args.includes('--child-uid') || !args.includes('--output') || !/^[A-Za-z0-9_-]{1,128}$/.test(parent || '') || !/^[A-Za-z0-9_-]{1,128}$/.test(child || '') || parent === child || !output) throw new Error('Supply the two verified, distinct anonymous UIDs and an output path. No defaults or inferred identities.');
const rules = `rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function parent() { return request.auth != null && request.auth.uid == ${JSON.stringify(parent)}; }
    function child() { return request.auth != null && request.auth.uid == ${JSON.stringify(child)}; }
    function commandKeys() { return ['refreshRequestedAt','refreshExpiresAt','refreshRequestedBy','locationReminderRequestedAt','locationReminderExpiresAt']; }
    function serverKeys() { return ['wakeBackendFor','wakeBackendAt','wakeBackendResult','wakeDispatchFor','wakeDispatchAt','wakeDispatchResult','wakeDispatchMessageId','wakeLeaseOwner','wakeLeaseUntil','wakeLeaseFor','wakeDispatchToken','wakeDispatchTokenHash']; }
    function parentCommandValid() {
      return !request.resource.data.diff(resource.data).affectedKeys().hasAny(['refreshRequestedAt','refreshExpiresAt','refreshRequestedBy']) ||
        (request.resource.data.refreshRequestedBy == request.auth.uid && request.resource.data.refreshRequestedAt is int &&
         request.resource.data.refreshRequestedAt >= resource.data.get('refreshRequestedAt',0) &&
         request.resource.data.refreshExpiresAt == request.resource.data.refreshRequestedAt + 900000);
    }
    function childTokenValid() {
      return !request.resource.data.diff(resource.data).affectedKeys().hasAny(['fcmToken','fcmTokenGeneration','fcmTokenOwnerUid']) ||
        (request.resource.data.fcmTokenOwnerUid == request.auth.uid && request.resource.data.fcmToken is string &&
         request.resource.data.fcmTokenGeneration is int && request.resource.data.fcmTokenGeneration >= resource.data.get('fcmTokenGeneration',0));
    }
    match /devices/child-01 {
      allow read: if parent() || child();
      allow create: if (parent() && request.resource.data.keys().hasOnly(commandKeys()) && request.resource.data.refreshRequestedBy == request.auth.uid && request.resource.data.refreshExpiresAt == request.resource.data.refreshRequestedAt + 900000) ||
        (child() && !request.resource.data.keys().hasAny(commandKeys()) && !request.resource.data.keys().hasAny(serverKeys()) && (!request.resource.data.keys().hasAny(['fcmToken']) || request.resource.data.fcmTokenOwnerUid == request.auth.uid));
      allow update: if (parent() && request.resource.data.diff(resource.data).affectedKeys().hasOnly(commandKeys()) && parentCommandValid()) ||
        (child() && !request.resource.data.diff(resource.data).affectedKeys().hasAny(commandKeys()) && !request.resource.data.diff(resource.data).affectedKeys().hasAny(serverKeys()) && childTokenValid());
      allow delete: if false;
      match /events/{eventId} {
        allow read: if parent() || child();
        allow create, update: if child() && request.resource.data.type is string && request.resource.data.id is string && request.resource.data.time is int;
        allow delete: if false;
      }
    }
    match /{document=**} { allow read, write: if false; }
  }
}
`;
mkdirSync(dirname(output), { recursive: true }); writeFileSync(output, rules, 'utf8');
console.log('Review-only rules file generated. Nothing deployed.');
