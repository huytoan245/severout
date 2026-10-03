# Family Location v2.2.10 — candidate validation, 2026-10-03

**This is an unsigned reliability candidate, not an installable signed release.** Physical acceptance criteria A–J remain unverified wherever they require Samsung, production Firebase or installation. Do not replace an installed v229 APK with an unsigned file.

## Nguyên nhân

Audit of reconstructed v229 found a crash window between asynchronous SharedPreferences engine state and asynchronous journal writes; timestamp-only sample collision; missing restart confirmation candidates; refresh marked consumed before GPS completion; one-shot auth failure; geofence remove/add gap and stale-heartbeat false liveness; concurrent cellular callback/probe races and retained binding after VPN arrival. Wake backend lacked request-age expiry and a concurrency claim, skipped wake to a rotated token after prior dispatch, and could invalidate a newly rotated token on a delayed old-token error. Full lint additionally exposed two API-28 calls on minSdk 26.

## Đã sửa

- Append v2210 to the existing v222→v229 patch chain; versionName 2.2.10, code 32. Add portable reconstruction and CI workflow. No app rewrite.
- SQLite v2 adds journal identity/meta tables while preserving all v1 pending rows. Valid GPS sample, lifecycle events and complete compact engine checkpoint commit in one FULL-synchronous transaction before upload. Insert failures throw; state rolls back on transaction failure. Network transition events and offline-state metadata also commit together. Sample IDs include original GPS time and coordinate bits. Confirmed identity keys survive row deletion/restart, and legacy remote IDs remain unchanged.
- Persist departure/arrival confirmation candidates and last trip/sample anchor. Emit the initial trip point. Preserve original GPS time, use restored accepted anchor for motion without speed, validate finite coordinates/accuracy, stale/out-of-order fixes and impossible jumps.
- Persist refresh request before ACK; retain terminal payload until authoritative Firestore/HTTPS success; resume interrupted acquisition after auth/restart. Use TTL 15min and a single cancellable immediate acquisition. Older/duplicate FCM cannot replace a newer pending request. Parent shows received/service/locating stages in both transports, avoids wake-stage regression after ACK, and retains its 45s timeout.
- Retry auth/listener registration, recover location-registration failures, guard late callbacks/executor submissions at teardown and record a live-service flag. A fresh heartbeat persisted by a dead process is no longer proof of liveness.
- Replace geofence by the same request ID, eliminating explicit remove/add gap; single registration flight, initial EXIT trigger, triggering-position re-arm, bounded goAsync receiver lifetime and watchdog re-arm. Background FGS permissions/exemptions still apply.
- Serialize cellular callbacks on main; reject stale/lost network probe results, allow only one active probe, throttle failure hints, release cellular binding when VPN becomes active. Three spaced actual failed server operations can request cellular even if a generic Firestore-host probe succeeds. Success resets the counter. Adaptive healthy/degraded intervals and existing hysteresis remain.
- Bound HTTPS executor queue; parallel transport capacity and Connection: close reduce old-route socket reuse and queue starvation. Poll at 120s with a healthy listener, 15s when degraded/refresh pending. This is not a guarantee that Firebase SDK sockets switch instantly; physical routing tests remain required.
- Backend transaction lease, expiry, current-request/token checks, token-specific dispatch dedupe, guarded invalidation and transient retries. FCM remains at-least-once; duplicates around a send/diagnostic-write crash remain possible and require Child idempotency. Runtime Node22, pinned admin/functions versions and committed lockfile; dependency audit returned zero known vulnerabilities at validation time.
- Add requested endurance diagnostic fields, heartbeat-success evidence and exit importance captured on service creation, with existing Parent Health and internal logging preserved. Fix API26/27 Location checks with LocationManagerCompat.

## Cơ chế phục hồi

Android sticky service restart; BOOT_COMPLETED after unlock/package replacement; persisted JobScheduler/WorkManager watchdog; Google Play services geofence EXIT; external deployed Firestore→FCM wake. Each permitted start restores DB checkpoint, GPS, listener/auth, heartbeat, token sync, network manager and pending upload. System/Play-services/backend ownership provides independent triggers; execution returns to the one app process. No mechanism bypasses force stop, revoked permission or OEM restrictions. The A–M matrix is in CURRENT_ARCHITECTURE_AND_FAILURE_MODES.md.

## Bảo toàn

com.family.parent / com.family.child, fixed child-01 and one Parent/Child; Parent name Kết Nối; both exact Child safety sentences and clock; one generic setup prompt; 220 tips; journey and original timestamp backfill; offline journal; cellular process binding/VPN respect; Parent Health; six-hour manual reminder gate, cooldown/TTL, existing channel semantics; silent foreground ID42, distinct wake ID22942 and reminder ID77; no low-battery warning; no old-data clearing or signer replacement.

## Đã kiểm tra

- Exact baseline reconstruction through all nine patches and appended v2210; static product/recovery validation and source diff whitespace checks.
- Existing coreSelfCheck and scenarioCheck executed successfully.
- Eight Robolectric API28 tests: 150-event persistence/reopen/replay; atomic rollback; same-time coordinate identity; v1 SQLite migration/legacy IDs; departure restart/trip point continuity; arrival restart; command/result durability; bad accuracy/stale/out-of-order/NaN/impossible jump rejection.
- Ten Node backend tests: payload/TTL; unordered/duplicate event; concurrent lease; expired/future request; late token; rotation after sent; old-token error race; invalid/transient error; owner crash lease expiry; terminal/diagnostic-event suppression. Loaded actual Firebase function export and inspected gen2/document/region/retry metadata.
- Both Android release APKs assembled; full lintRelease run on both modules (0 errors; Child 91 and Parent 12 warnings, largely baseline dependency/style/SDK warnings). Warnings were not suppressed to force a pass.
- APK ZIP integrity, zipalign, manifest package/versionCode/versionName verified. Reference v229 Parent and Child signatures verify and match the required signer fingerprint. Newly generated v2210 APKs are unsigned.
- Firebase production inspection attempted with CLI15.32.1; authentication failed. This is evidence of a missing authenticated inspection, not evidence that the backend is absent.

## Chưa xác minh

Production deployment/real trigger/FCM receipt/ACK/fresh GPS; Android service/sticky/Doze/standby/reclaim/reboot/update on device; Play-services geofence; SIM routing/DNS/Auth/Firestore/VPN/socket recreation; Samsung OEM deep sleep and 24–48h endurance; new APK signature/install/update compatibility. No ADB device or emulator was available. Full requested 30-case evidence matrix is TEST_MATRIX_V2210.md.

## Rủi ro còn lại

Unsigned artifacts cannot update installed apps. Backend deploy requires the user's authenticated Firebase project access. OEM/Doze can defer every recovery path; force stop cannot be bypassed. Missing GPS fixes while Android withholds execution cannot be reconstructed later. Full SQLite transactions on accepted fixes prioritize durability and need physical battery/latency measurements; disk exhaustion can prevent new data writes. Retained identity keys grow over time. An unexpired backend lease can delay dispatch up to 60s after owner crash; Parent timeout is 45s, so a later wake may arrive after visible timeout. Parent retains a 5000-event display window; older history remains in server/journal but full archival pagination is unchanged. Firestore security rules remain the existing development baseline and were not deployed or tightened in this change.

## File release

Family-Parent-v2.2.10-unsigned.apk and Family-Child-v2.2.10-unsigned.apk; candidate ZIP and SHA256SUMS supplied alongside them. No Installable.apk is claimed. scripts/sign-release.ps1 requires the existing local signing process and refuses promotion when fingerprint differs from 4675c26756f33870024ea10a00ec300d1329d37bb3c9f169a25e9f4a6e5bdbbd. Production deployment and endurance instructions are in PRODUCTION_WAKE_AND_ENDURANCE.md. Draft PR is based on v229; master is not merged.
