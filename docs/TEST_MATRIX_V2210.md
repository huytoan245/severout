# v2.2.10 evidence matrix

Host: Windows; Android SDK 36; Gradle 9.5.0/JDK 17; Robolectric API 28. No physical device/emulator attached. Unit tests use Android/SQLite shadows; they do not measure GPS, Doze, GMS geofence latency or Samsung survival. Backend tests inject an in-memory serialized transaction fake, not production Firestore/FCM.

|Requested test|Evidence/remaining test|
|---|---|
|1 App start|Android build/static wiring; actual launch NOT VERIFIED|
|2 App background|NOT VERIFIED (device)|
|3 Screen off for hours|NOT VERIFIED (Samsung endurance)|
|4 Ordinary process kill|NOT VERIFIED (device, not force-stop)|
|5 Service restart|Checkpoint restart unit test; actual service lifecycle NOT VERIFIED|
|6 START_STICKY|Static retained; system restart NOT VERIFIED|
|7 Doze|NOT VERIFIED (ADB/device)|
|8 App standby|NOT VERIFIED (ADB/device)|
|9 Reboot|Checkpoint persistence tested; broadcast/GPS restoration NOT VERIFIED|
|10 APK update|v1 SQLite migration tested; signer/install/update NOT VERIFIED|
|11 FCM high priority|Backend payload tested; actual delivered HIGH NOT VERIFIED|
|12 FCM downgraded|Delivered-priority gate inspected; actual NORMAL delivery/recovery NOT VERIFIED|
|13 Duplicate FCM|Backend dedupe/concurrency tested; actual Child receipt NOT VERIFIED|
|14 Token rotation|Backend late-token/rotation/invalidation-race tests; real FCM SDK rotation NOT VERIFIED|
|15 Backend retry|Transient retry, lease timeout, concurrent owner tests; cloud retry NOT VERIFIED|
|16 Offline durable command|SQLite command/result persistence tested; Firestore offline/FCM flow NOT VERIFIED|
|17 Wi-Fi lost|Static callback recovery inspected; real disconnect NOT VERIFIED|
|18 Cellular failover|Static binding/VPN/stale-cell guards inspected; SIM/DNS/socket traffic NOT VERIFIED|
|19 Wi-Fi restored|Existing hysteresis preserved; runtime NOT VERIFIED|
|20 Wi-Fi + mobile offline|Journal fault/reopen test; radio/network test NOT VERIFIED|
|21 Offline GPS journal|150 synthesized points committed/reopened in Robolectric; actual GPS NOT VERIFIED|
|22 Reconnect backfill|Deterministic identity/retry/delete tested locally; actual server/Parent backfill NOT VERIFIED|
|23 Parent refresh Child background|UI timeout/stages inspected; device E2E NOT VERIFIED|
|24 Parent refresh after reclaim|NOT VERIFIED (production FCM/device)|
|25 Movement after reclaim/geofence|Same-ID registration and receiver lifetime inspected; genuine GMS event NOT VERIFIED|
|26 100+ GPS points|150 generated route events persisted/reopened/replayed with 150 distinct IDs|
|27 Duplicate prevention|Replay after deletion/reopen and same-timestamp distinct coordinates tested|
|28 Restart during pending upload|Pending rows survive reopen, failed batch rolls back; actual network process kill during upload NOT VERIFIED|
|29 Bad accuracy|NaN/151m accuracy and stale/out-of-order/NaN-coordinate rejection unit test|
|30 Impossible jump|111km/30s jump rejected in LocationPolicy unit test|

No row labeled NOT VERIFIED is accepted as PASS by inference. Parent/Child compile and full lint are separately recorded in release validation report. OEM, installed signer and production evidence must be appended after actual execution.
