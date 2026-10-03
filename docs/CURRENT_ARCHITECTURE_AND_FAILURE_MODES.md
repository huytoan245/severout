# Current Architecture & Failure Modes — audited before implementation

Date: 2026-10-03. Baseline: v229-always-alive-recovery, 30e13b0e2c959364be465cb373e3d47b9404087a (handoff PR #23).
Evidence: reconstructed appsrc with the exact v229 workflow preparation and nine patches, not raw ci-patches/LocationService.kt. No AGENTS.md exists in this checkout.

## P0: production wake deployment
The supplied ZIP contains signed v229 APKs and function source, not Firebase administrative credentials or signer. No Firebase CLI login/ADC was found in the conventional local locations. No ADB device is connected. Production deployment, trigger, FCM delivery, ACK and fresh GPS are NOT VERIFIED. Prepare an explicit project-scoped inspect/deploy script and an evidence checklist; never label compile/static checks as end-to-end PASS.

## Existing architecture
Android single location foreground service (ID 42, protection channel, silent) owns one FusedLocation callback, Firestore command listener, HTTPS command fallback, local heartbeat and process network failover. START_STICKY is a system restart request. JobScheduler/WorkManager persists watchdog/token jobs. Google Play services owns a mutable explicit geofence PendingIntent (250m EXIT). FCM and Firestore backend provide an external command wake path. BOOT_COMPLETED and MY_PACKAGE_REPLACED restore mechanisms after unlock. LOCKED_BOOT_COMPLETED appears in receiver code but is not declared/direct-boot capable; encrypted journal cannot safely open before unlock.

## Concrete source defects
1. GPS handle() mutates engine and saves SharedPreferences asynchronously BEFORE asynchronous SQLite event writes. Process death in that gap can persist state without the corresponding visit/trip/sample journal. Device upload also begins before sample insertion. SQLite insert() ignores failure return. No atomic checkpoint/events transaction exists.
2. Local sample ID is only sample-timestamp; uploader document ID also depends on timestamp. Different coordinate samples at the same millisecond collide. Restart loses last sample anchor. Duplicate queued logical events have no local unique identity.
3. StateStore restores only visit/trip identity, losing departure/arrival candidates and last trip point. A reboot in movement/arrival confirmation changes lifecycle timing. Trip starts with a point but emits no initial TripPointAdded event. Monotonic/out-of-order and finite coordinate checks are incomplete; accepted anchor is transient across reclaim.
4. Refresh last_refresh_seen is persisted before acquisition and pending FCM is removed before success. Crash after ACK prevents the same durable command from resuming. Duplicate older FCM overwrites pending newer request. No client/backend age validation. Startup/network/onStart may launch overlapping current-location requests.
5. Backend has no request-age expiry, no concurrency claim, dedupes all sent requests regardless of token rotation, and can clear a rotated healthy token after a delayed error on the old token. Send succeeds then diagnostic write fails can repeat sends. Retry is at-least-once: client dedupe remains mandatory.
6. Anonymous auth fails once at onCreate; reconnect/route recovery does nothing when currentUser is still null. Token worker may authenticate later without restoring service listeners. A dead listener is not explicitly retried. Location registration task failures can leave trackingActive true even when registration failed.
7. Geofence removes existing fence before add: failure/crash leaves no fence. Concurrent registrations race. Re-arm uses stale stored center on EXIT instead of triggeringLocation; no receiver goAsync lifetime for async registration. Permission failure lacks registration diagnostic.
8. NetworkCallback fields mutate off main thread while probing mutates on main. Stale cellular probe can bind a lost network. VPN discovered after failover does not release binding. Repeated probeNow can queue probes faster than execution. Firestore route restart callbacks can revive work after service destroy.
9. HTTPS requests all share one unbounded single-thread queue, always poll every 15s even with healthy listener, and reuse HTTP pooled sockets after process binding. This can delay ACK/journal behind stale offline work and retain old routes.
10. Parent wakeDispatch UI runs after ACK UI and overwrites more advanced progress. Parent already has a 45s timeout; retain it and add explicit stages to both transports. Several requested diagnostic aliases/success timestamps/exit importance are missing.

## Minimal implementation scope
Preserve baseline patch chain and append v2210 hardening; no app rewrite. Add atomic SQLite journal/checkpoint migration preserving v1 rows, deterministic identity, complete compact engine snapshot, monotonic finite GPS validation, durable restartable refresh with TTL and one active acquisition, auth/listener recovery, geofence replace-by-same-ID with bounded receiver lifetime, serialized network callbacks/probes and VPN unbind, bounded HTTPS queues and adaptive fallback, concurrency-safe backend TTL/token guards with tests, evidence diagnostics and Parent stage display. Version 2.2.10/code 32 on v2210-always-active-reliability; no master merge.

## Recovery matrix (runtime results remain NOT VERIFIED)
|Scenario|Independent trigger -> service -> network/GPS/server|
|---|---|
|A service dies, process survives|onDestroy one-shot watchdog; system sticky restart; persisted local state restored; route manager/listener recreated|
|B process reclaimed|Android sticky restart, GMS geofence EXIT, FCM high priority, persisted JobScheduler watchdog; local journal/checkpoint survives|
|C deep Doze|FCM delivered HIGH or allowed geofence/system restart; jobs may be deferred; no latency guarantee|
|D standby|same external paths, subject to OS bucket/quota and background location permission|
|E Samsung restricted/deep sleep|diagnostic evidence; OEM can suppress jobs/FCM/GMS; requires user configuration, no bypass|
|F reboot|BOOT_COMPLETED after unlock -> jobs/token/geofence/service when OS permits -> restore SQLite -> GPS -> flush|
|G APK update|MY_PACKAGE_REPLACED -> same recovery; signer/version compatibility required|
|H validated Wi-Fi fails Firebase|actual operation failure -> targeted Wi-Fi probes -> request cellular -> bind only app -> reopen Firebase/HTTPS|
|I Wi-Fi disappears, SIM available|default cellular/network callback; bound-cell lost releases -> default route -> auth/listener/flush|
|J both offline|GPS -> SQLite transaction; transports bounded/back off; pending rows retained|
|K hours later reconnect|network callback/heartbeat -> auth retry -> listener/flush deterministic documents -> timestamp order Parent|
|L Parent refresh asleep|durable command -> deployed backend -> FCM -> service if legal -> ACK/stage -> fresh GPS -> authoritative completion|
|M movement after reclaim|GMS EXIT -> receiver -> legitimate location FGS -> restore checkpoint and journal -> GPS; cannot reconstruct unobserved points while OS withheld execution|

## Required evidence and limitations
Host tests cover code paths, queue migration and fault cases; they do not simulate Samsung. Android build/lint must complete. APK signer must match 4675c26756f33870024ea10a00ec300d1329d37bb3c9f169a25e9f4a6e5bdbbd. No replacement signer. Installation/update, screen-off, Doze, standby, reboot, genuine Play services geofence and FCM need a device. 24–48h endurance remains NOT VERIFIED until physical evidence is obtained. Force Stop/permission revocation/system Location OFF are platform/user limits.

References checked: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/develop/background-work/services/fgs/service-types ; https://firebase.google.com/docs/functions/retries ; https://firebase.google.com/docs/cli . Background location and an eligible trigger are separate requirements; WorkManager is not an exemption by itself.
