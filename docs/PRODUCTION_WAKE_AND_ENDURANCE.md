# Production wake and Samsung endurance evidence

## Actual local inspection
2026-10-03: Firebase CLI 15.32.1 `functions:list --project family-location-884e5 --non-interactive` returned `Failed to authenticate, have you run firebase login?`. No deployment conclusion can be drawn. No connected ADB devices. Physical Samsung, FCM delivery, deployed backend, update installation and 24–48h endurance remain **NOT VERIFIED**.

## Exact deployment path
Use Node 22 and a browser login for an account with permission on family-location-884e5. Do not paste tokens/service-account/signing credentials into chat or repository. The prepared backend pins firebase-admin14.5.0/functions7.4.0 with a lockfile. Runtime `nodejs22` is supported by the [Firebase runtime documentation](https://firebase.google.com/docs/functions/manage-functions#node.js_version).

```powershell
cd firebase-wake
npx --yes firebase-tools@15.32.1 login
.\deploy.ps1            # inspect first
.\deploy.ps1 -Deploy    # deploy only wakeChildOnRefresh
npx --yes firebase-tools@15.32.1 functions:log --only wakeChildOnRefresh --project family-location-884e5
```

The script scopes every Firebase operation to this exact project; it does not deploy Firestore rules or delete functions. Verify the listed deployed function is gen2, asia-southeast1, Firestore document-update trigger for `(default)/devices/child-01`, retry enabled. If an existing function has a different immutable trigger/region, inspect before migrating; do not delete it blindly.

## Production end-to-end acceptance
On configured Child (precise/background location, Unrestricted, Never sleeping, unused-app pause off), open once and check server FCM token confirmation. On Parent press Refresh and capture one request ID. Correlate `refreshRequestedAt/refreshExpiresAt` -> `wakeDispatchFor/Result/At/MessageId` -> `fcmWakeRequestId/ReceivedAt/DeliveredPriority` -> `refreshReceivedFor` -> `refreshServiceFor` -> `refreshLocatingFor` -> `refreshCompletedFor` plus `locationTime`, accuracy and coordinates. `sent` means FCM accepted dispatch, not device delivery. Parent must time out visibly after 45 seconds if terminal evidence is absent. A request remains durable for 15 minutes; Parent timing out does not cancel a valid offline command.

Repeat with process reclaimed (not force stopped), delivered NORMAL priority, duplicate request ID, rotated token and offline request. NORMAL priority with a living service can reach the listener/poller; a dead service without an OS exemption must wait for an allowed recovery trigger. Capture explicit blocked-prerequisite/start-exception evidence rather than claim recovery.

## 24–48h Samsung log plan
Keep timestamps/device timezone. Record baseline APK SHA/signer, device/Android version and configuration. At 0h, 2h, 8h, 24h, 48h capture server diagnostics. Move along a known route; disable all networking while moving, reboot/update with pending queue, reconnect and compare point/event IDs and original GPS times. Keep location enabled for route tests. Record system Location OFF/reminder tests separately.

Fields: serviceInstanceId, serviceStartCount/RestartCount, lastLocalServiceHeartbeatAt, lastGpsCallbackAt, lastCloudHeartbeatAttemptAt/SuccessAt, processExitReason/Importance, watchdogLastRunAt/Result/Trigger, geofenceRegisteredAt/TriggeredAt/result, fcmTokenConfirmedAt, fcmWakeReceivedAt/request/priority, routeMode, cellularFailoverActive, pendingJourneyCount, syncState/count, locationTime.

Interpretation: stale local heartbeat + process exit/restart evidence supports service/process loss; advancing local heartbeat/GPS with failed cloud attempts and pending rows supports transport failure; advancing service heartbeat but no GPS callback with permissions/location on supports GPS/provider failure. Server-only stale snapshots cannot prove the latest local cause while offline. Retrieve local diagnostic preferences via debug/authorized lab instrumentation or capture the values after reconnect; release is not made debuggable.

## ADB lab recipe (not executed here)
Select the Child by serial. `am force-stop` is NOT a normal reclaim test; it deliberately disables automatic recovery.

```text
adb -s CHILD_SERIAL shell input keyevent KEYCODE_HOME
adb -s CHILD_SERIAL shell input keyevent KEYCODE_SLEEP
adb -s CHILD_SERIAL shell dumpsys deviceidle force-idle
adb -s CHILD_SERIAL shell dumpsys deviceidle unforce
adb -s CHILD_SERIAL shell am set-standby-bucket com.family.child rare
adb -s CHILD_SERIAL shell am set-standby-bucket com.family.child active
adb -s CHILD_SERIAL shell svc wifi disable
adb -s CHILD_SERIAL shell svc data disable
adb -s CHILD_SERIAL shell svc data enable
adb -s CHILD_SERIAL shell svc wifi enable
adb -s CHILD_SERIAL reboot
adb -s CHILD_SERIAL install -r Family-Child-v2.2.10-Installable.apk
```

Ordinary process reclaim requires OS memory pressure or an authorized rooted test image kill; `am kill` may refuse a foreground-service process. Restore idle/bucket/network state after each lab test. AOSP/emulator does not reproduce Samsung deep sleep, Play-services delivery or router-specific Firebase path faults.
