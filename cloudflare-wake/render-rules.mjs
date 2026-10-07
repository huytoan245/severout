import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
const args = process.argv.slice(2);
const value = key => args[args.indexOf(key) + 1];
const output = value('--output');
if (!args.includes('--output') || !output || args.some(x => x === '--parent-uid' || x === '--child-uid')) throw new Error('Only --output is supported. Authorization uses the server-managed family mapping.');
const rules = `rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function family() { return get(/databases/$(database)/documents/families/family-01).data; }
    function parent() { return request.auth != null && family().familyId == 'family-01' && family().childDeviceId == 'child-01' && family().get('parentUid','') != family().get('childUid','') && request.auth.uid == family().get('parentUid',''); }
    function child() { return request.auth != null && family().familyId == 'family-01' && family().childDeviceId == 'child-01' && family().get('parentUid','') != family().get('childUid','') && request.auth.uid == family().get('childUid',''); }
    function commandKeys() { return ['refreshRequestedAt','refreshExpiresAt','refreshRequestedBy','refreshEpoch','locationReminderEpoch','locationReminderRequestedAt','locationReminderExpiresAt']; }
    function serverKeys() { return ['parentUid','childUid','parentKey','childKey','familyId','childDeviceId','epoch','locked','wakeBackendFor','wakeBackendAt','wakeBackendResult','wakeDispatchFor','wakeDispatchAt','wakeDispatchResult','wakeDispatchMessageId','wakeLeaseOwner','wakeLeaseUntil','wakeLeaseFor','wakeDispatchToken','wakeDispatchTokenHash']; }
    function parentCommandValid() {
      return !request.resource.data.diff(resource.data).affectedKeys().hasAny(['refreshRequestedAt','refreshExpiresAt','refreshRequestedBy','refreshEpoch']) ||
        (request.resource.data.refreshEpoch == family().epoch && request.resource.data.refreshEpoch == family().epoch && request.resource.data.refreshRequestedBy == request.auth.uid && request.resource.data.refreshRequestedAt is int &&
         request.resource.data.refreshRequestedAt >= resource.data.get('refreshRequestedAt',0) &&
         (request.resource.data.refreshRequestedAt > resource.data.get('refreshRequestedAt',0) || request.resource.data.refreshEpoch == resource.data.get('refreshEpoch',0)) &&
         request.resource.data.refreshExpiresAt == request.resource.data.refreshRequestedAt + 900000);
    }
    function stageValid(changed,key,epoch) {
      return !changed.hasAny([key]) ||
        (resource.data.get('refreshEpoch',0) == epoch && request.resource.data[key] is int && request.resource.data[key] == resource.data.get('refreshRequestedAt',0) && request.resource.data[key] >= resource.data.get(key,0));
    }
    function childStateValid() {
      let changed = request.resource.data.diff(resource.data).affectedKeys();
      let epoch = family().epoch;
      return (!changed.hasAny(['locationReminderAckFor','locationReminderAckAt','locationReminderResult']) || resource.data.get('locationReminderEpoch',0) == epoch) &&
        (!changed.hasAny(['refreshResult']) || resource.data.get('refreshEpoch',0) == epoch) &&
        stageValid(changed,'refreshReceivedFor',epoch) && stageValid(changed,'refreshServiceFor',epoch) && stageValid(changed,'refreshAckFor',epoch) &&
        stageValid(changed,'refreshLocatingFor',epoch) && stageValid(changed,'refreshPersistedFor',epoch) && stageValid(changed,'refreshUploadedFor',epoch) &&
        stageValid(changed,'refreshCompletedFor',epoch) && stageValid(changed,'refreshFailedFor',epoch) &&
        (!changed.hasAny(['locationTime']) ||
          (request.resource.data.locationTime is int && request.resource.data.locationTime >= resource.data.get('locationTime',0))) &&
        (!changed.hasAny(['refreshResult']) ||
          !(resource.data.get('refreshCompletedFor',0) == resource.data.get('refreshRequestedAt',-1) || resource.data.get('refreshFailedFor',0) == resource.data.get('refreshRequestedAt',-1)) ||
          request.resource.data.refreshResult == resource.data.get('refreshResult',''));
    }
    match /families/family-01 {
      allow read: if parent() || child();
      allow write: if false;
    }
    match /devices/child-01 {
      allow read: if parent() || child();
      allow create: if (parent() && request.resource.data.keys().hasOnly(commandKeys()) && request.resource.data.refreshEpoch == family().epoch && request.resource.data.refreshRequestedBy == request.auth.uid && request.resource.data.refreshRequestedAt is int && request.resource.data.refreshRequestedAt > 0 && request.resource.data.refreshExpiresAt == request.resource.data.refreshRequestedAt + 900000) ||
        (child() && !request.resource.data.keys().hasAny(commandKeys()) && !request.resource.data.keys().hasAny(serverKeys()) && !request.resource.data.keys().hasAny(['fcmToken','fcmTokenGeneration','fcmTokenOwnerUid','fcmTokenUpdatedAt','fcmTokenVersion','wakeProtocolVersion']));
      allow update: if (parent() && request.resource.data.diff(resource.data).affectedKeys().hasOnly(commandKeys()) && parentCommandValid() && (!request.resource.data.diff(resource.data).affectedKeys().hasAny(['locationReminderRequestedAt','locationReminderExpiresAt','locationReminderEpoch']) || (request.resource.data.locationReminderEpoch == family().epoch && (request.resource.data.locationReminderRequestedAt > resource.data.get('locationReminderRequestedAt',0) || request.resource.data.locationReminderEpoch == resource.data.get('locationReminderEpoch',0))))) ||
        (child() && !request.resource.data.diff(resource.data).affectedKeys().hasAny(commandKeys()) && !request.resource.data.diff(resource.data).affectedKeys().hasAny(serverKeys()) && !request.resource.data.diff(resource.data).affectedKeys().hasAny(['fcmToken','fcmTokenGeneration','fcmTokenOwnerUid','fcmTokenUpdatedAt','fcmTokenVersion','wakeProtocolVersion']) && childStateValid());
      allow delete: if false;
      match /events/{eventId} {
        allow read: if parent() || child();
        allow create: if child() && request.resource.data.type is string && request.resource.data.id is string && request.resource.data.time is int;
        allow update: if child() && request.resource.data == resource.data;
        allow delete: if false;
      }
    }
    match /{document=**} { allow read, write: if false; }
  }
}
`;
mkdirSync(dirname(output), { recursive: true }); writeFileSync(output, rules, 'utf8');
console.log('Review-only rules file generated. Nothing deployed.');
