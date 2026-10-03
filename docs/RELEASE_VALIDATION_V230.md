# Family Location v2.3.0 – Clean Reliable Baseline

03/10/2026. versionName2.3.0/versionCode34. Audited baseline8f648142d67e275bfb571f6111f05f240ef97408. Branch v230-clean-reliable-baseline, new Draft PR against v2211; no master edits/merge, no production changes. **Unsigned candidate only: new signer has not been created, no Installable APK or signed Release ZIP.**

## Nguyên nhân

Audit trước code: ARCHITECTURE_AUDIT_V230.md. v2211 Android source was generated/ignored, making direct fixes easy to overwrite. Fresh Child never requested runtime permissions. FCM lacked fixed-device/expiry payload binding and checked durable-inbox dedupe. SQLite sample/checkpoint ran on main looper. Child latest GPS/ACK SDK+REST writes could arrive out of order. Old cloud token revision could block clean-install identity, and an extra Activity token lookup could race rotation. Network callback/probe gate could lose cellular eligibility or apply stale callbacks; exception paths lacked disconnect. Parent had fewer progress stages and a non-monotonic local clock. Active signer script required an unavailable old key.

## Đã sửa

- Canonical Android source tracked under appsrc, with pre-change54-file hashes; default reconstruction never mutates it. Legacy patch chain remains historical and its reconstructor refuses overwriting v230. Dependencies unchanged from validated v2211 (Gradle9.5/JDK17/SDK36/min26/target36).
- One generic app prompt, separate real Android fine/coarse → notification → background → battery system flow after consent. API30+ background permission uses app settings. Dismissal does not request/grant permissions. No repeated Child setup card. Missing permissions are published by token worker even if FGS cannot start; technical states stay in Parent Health/internal logs. Mandatory Android disclosures remain. Backup disabled to avoid automatically restoring old local identity/token/journal into clean installs; existing cloud rows are untouched.
- Dedicated serial journal HandlerThread receives continuous GPS callbacks and restores checkpoint/pending/network metadata; upload executor is separate. Every accepted raw sample and engine events/checkpoint commit before cloud publication. Transactional wake inbox and terminal/pending update; strict matching device/type/request/timestamps/expiry, duplicate recovery without repeated ACK/notification. Explicit close helper avoids AutoCloseable casts on supported API26–28.
- Shared DeviceMutationPolicy protects latest location and matching request stages in Firestore transaction and HTTPS updateTime CAS. Old terminal cannot clear a newer pending request. Parent rejects older location snapshots. ACK/service/GPS/local-persisted/uploaded/terminal stages use request evidence; outbox commit and Firestore command remain separate. Parent request clock is durable and locally monotonic;45s timeout remains.
- Token CAS rebase moves above existing cloud revision, rejects obsolete local snapshots, preserves rotation confirmation guards and exact runtime owner. Late Activity token lookup uses expected generation. Rules renderer uses future verified new UIDs; no guessing/deploy. Existing restrictive rules may block new clients until reviewed. Public UID-only FamilyIdentity log supports accurate provisioning when old rules block Parent reads.
- Cellular requests/probes use callback identity/generation, bounded probe queue and per-network gate; late unavailable/lost callbacks cannot clear replacement. VPN cancels pending binding as well as active binding. HTTP sockets close in finally. Existing healthy60s/degraded15s probe, fast actual-error signal, app-only cellular binding and cleanup retained.
- Exact public origin defaults to https://family-location-wake.huytoan0979928450.workers.dev; build/runtime validate HTTPS origin/no endpoint and append /v1/wake once. Worker2.3.0 verifies token lifetime ordering and sends explicit child-01/requestedAt/expiresAt payload. Existing RS256/exact-UID/rate12/min/bounded8 attempts/TTL/SQLite alarms/rotation/invalid-token/at-least-once behavior remains.
- Rules prevent stale request ACK/locationTime/token revision, same-revision token replacement, client backend spoof and history mutation. Existing events read by Parent, retry identical events allowed, deletes denied. No production rules/data/users modified.
- New signer scripts accept only secure local input/environment, keystore outside repo, standard Family-Location-Release-2026.jks, pin the newly verified public fingerprint and reject another signer; stage both APKs before promoting either. Password/key not generated or printed. Current fingerprint NOT_CREATED; real key creation/signature/install gate remains stopped by user instruction.

## Bảo toàn

com.family.parent/com.family.child, family-location-884e5, child-01, one Parent+Child, Kết Nối, Journey and old cloud events, journal v1→v2 migration, Visit/Trip original GPS time/identity, app-only Wi-Fi→cellular/VPN respect, Health, reminder6h gate/cooldown/TTL/channel/notification semantics, silent protection42 vs wake22942 vs reminder77, dark Child clock1–12/shield/animation/220tips and exact two safety sentences. No low-battery warning/multi-device/private Samsung API/permanent wakelock/exact alarm/Force Stop bypass. No old branches/releases deleted.

## Đã kiểm tra

|Requirement|Actual result/scope|
|---|---|
|A Static validation|PASS reconstruction/provenance/product/secret boundary/canonical source checks|
|B coreSelfCheck|PASS actual Gradle task|
|C scenarioCheck|PASS actual Visit/Trip scenario task; this is not a radio/device simulation|
|D Robolectric/unit|PASS26 API28 tests:8 existing journal/GPS +4 survival/token +10 clean baseline +4 network adapter|
|E SQLite migration/restart|PASS actual Robolectric SQLite v1→v2, checkpoint candidates/trip, inbox/reopen/terminal and atomic dedupe|
|F5000+ points|PASS5001 samples SQLite commit/reopen/ordered drain with batches≤128, engine anchor≤1; plus existing5000-point engine test. Not a handset RSS/battery measurement|
|G token rotation|PASS concurrent50 writers, obsolete read/confirm, new-install rebase cannot replace rotated token. Google SDK rotation on handset NOT VERIFIED|
|H duplicate FCM/request|PASS transactional inbox duplicates/reopen/older request, Worker concurrency/restart/idempotency; live FCM NOT VERIFIED|
|I malformed/expired FCM|PASS actual messaging-service early reject + parser wrong-device/expiry/future/malformed cases and backend rejection tests|
|J Location OFF|PASS recovery decision/policy simulation; real GPS-toggle/service behavior NOT VERIFIED|
|K permission missing|PASS actual RecoveryStarter blocked entry and setup diagnostics in Robolectric; system dialog/Android12–16 handset lifecycle NOT VERIFIED|
|L/N network offline/both offline|PASS adapter no-bind + SQLite offline retention/restart/ordered drain; physical radio outage NOT VERIFIED|
|M Wi-Fi bad/cellular good|PASS actual NetworkFailoverManager with Robolectric ConnectivityManager, injected probe results, cellular bind/lost cleanup. No SIM/DNS/Firebase sockets on handset tested|
|VPN/stale network callback|PASS cancellation of pending request and late old onUnavailable cannot unregister replacement in adapter simulation|
|O Firestore security|PASS32 real emulator assertions, fixture UIDs/demo project only|
|P Cloudflare backend|PASS23 actual Node tests incl real generated RSA verification, request/auth/rate/rotation/retry/expiry/HIGH payload|
|Q Worker dry-run/runtime|PASS Wrangler dry-run and local real workerd/SQLite binding/auth denial/durable acceptance/restart; no production/Google calls|
|R/S Parent+Child assembleRelease|PASS both actual APKs, packages unchanged/code34/version2.3.0|
|T full lint|PASS0 errors; Child102 warnings/Parent18 warnings, no error suppressions/baseline trick|
|Origin validation|PASS actual Gradle rejects HTTP and an origin including /v1/wake; public URL found in Parent APK DEX, no secret credential|
|APK integrity/zipalign|PASS unsigned ZIP integrity and16KiB zipalign on actual build artifacts; same bytes copied to candidate|
|New signature gate|PASS script parse/refuses missing pinned signer; actual apksigner verify on unsigned APK fails Missing META-INF as expected. **Signed signature verification NOT VERIFIED**|

CI status/exact commit, APK+ZIP SHA256, local logs/XML/lint reports and input-byte equality are recorded in delivery VALIDATION-EVIDENCE.json. Negative permission-denial emulator logs are expected assertions, not hidden test failures. Initial compile/test compatibility errors were corrected; PASS above refers to final successful runs.

## Chưa kiểm tra

New keystore creation/certificate pin/real signing/Installable/install acceptance; Android emulator/physical device (adb devices empty); new Parent/Child UIDs and real exact-UID rules migration; Cloudflare production secrets/IAM/Free plan/real Firebase Spark plan; Worker production/FCM arrival/downgrade/service/GPS/ACK E2E; process reclaim/Doze/App Standby/Samsung Sleeping/Deep Sleeping/reboot/package-replace, radio/VPN/DNS and24–48h movement/screen-off endurance. UI/device system dialogs are not inferred from compile or API28 shadows.

## Rủi ro còn lại

Ordinary recovery has independent Android job/sticky/boot, Google geofence and Cloudflare/FCM owners; actual execution remains subject to Android/OEM prerequisites. No claim of never being killed. LOCKED_BOOT_COMPLETED is not enabled because Firebase/journal/WorkManager are credential-encrypted and not direct-boot safe. User Force Stop/permissions revoked/Location OFF/OEM restrictions can stop all triggers. GPS not delivered or not yet durably committed at process death cannot be reconstructed; disk full prevents writes. Callback/journal queue is not durable before SQLite commit. Tests bound engine memory/DB drain batches, not total handset RSS under arbitrary producer overload.

Valid Firebase ID token revocation is not checked; exact new UID rules/secrets and normal token expiry are relied on. Worker send/store crash can duplicate FCM, inbox dedupe is required. FCM acceptance ≠ arrival; received ACK ≠ GPS. Free quotas/8-attempt15m limits can stop recovery. Old cloud command/location times far ahead of current clock may prevent monotonic progression; report/review explicit migration, never auto-delete or silently reset. Journal identity keys grow; existing Parent5000-event display window remains. Transaction guards cost extra reads; Spark quota and real battery/disk/latency require measurement.

## Release

Branch v230-clean-reliable-baseline. Draft PR against v2211, no merge. Unsigned Parent/Child2.3.0 candidates, Source ZIP, Candidate ZIP, SHA256 and evidence. No Family-*-Installable.apk or Family-Location-v2.3.0-Release.zip before signing gate.

**DỪNG tại signer:** theo mục14 yêu cầu người dùng, chưa có password/signing input an toàn. Người dùng chạy scripts/create-release-keystore.ps1 trực tiếp trên máy, nhập mật khẩu cục bộ; không gửi secret. Sau khi keystore/fingerprint có thật mới ký và verify cả hai app. Chưa uninstall/clear data hay deploy production.
