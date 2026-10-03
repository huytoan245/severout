# v2.2.11 / code33 candidate — 03/10/2026

**Unsigned candidate, public Worker URL intentionally unconfigured. Production deployment paused by the user.** This is not an installable signed or production-ready release. No replacement signer; no master merge. Baseline v2210: 37b021ed892a92ef5fa36eb02365e8de27c140f6. Full pre-change audit: CURRENT_ARCHITECTURE_AND_FAILURE_MODES_V2211.md. Exact Vietnamese setup steps: CLOUDFLARE_FREE_SETUP_V2211_VI.md.

## Nguyên nhân

Foreground promotion followed disk/SDK setup; outdated command-listener/route callbacks could replace current state; pre-31 expedited watchdog had no ForegroundInfo; a long trip retained/copied its entire point list in RAM; stalled GPS registration lacked a bounded provider check. Token worker could overwrite/confirm an obsolete token after rotation. Parent collected partial survival diagnostics but did not render them. Existing Firebase Cloud Function wake path required deployment outside the requested Spark-only architecture; no explicit external wake/outbox existed in Parent.

## Đã sửa

- Promote valid silent location FGS immediately before DB/SDK/job setup; retain sticky, one callback, one listener, stopWithTask=false and notification IDs/channels. Listener/route generations reject obsolete callbacks. Bound watchdog retries; regular persisted work on API26–30, expedited only on API31+. Boot/package-replace use shared prerequisite/reason recording and bounded receiver lifetime. Infrequent ten-minute GPS health acquisition does not claim quiet stationary callbacks prove provider death.
- Retain v2210 atomic local-first journal, v1 migration, original GPS timestamps/identities and authoritative removal. Keep only latest engine trip anchor in RAM while every raw sample/lifecycle/trip event stays in journal. Persist terminal refresh even if auth disappeared during GPS acquisition, for delivery after auth restores.
- Durable monotonic token generation; old worker cannot overwrite local rotation or confirm a newer token. Firestore transaction rejects stale generation; HTTPS updateTime CAS plus current-user/generation guard and GET read-back. Include actual fcmTokenOwnerUid. Keep SDK token/local persistence, retry/periodic jobs and existing received-priority/legal-start logic.
- Parent WorkManager outbox commits before UI command write. Worker and UI use guarded durable Firestore transaction; HTTPS fallback uses updateTime precondition so older commands do not overwrite newer. Parent external wake uses runtime Firebase ID token, a public build-configured HTTPS Worker origin, bounded transport, and at most one forced-token refresh on 401. No server secret in APK. Missing origin displays unconfigured status and remains unverified.
- One monotonic Parent stage renderer: command sent -> backend accepted -> FCM accepted -> Child received -> service -> GPS -> terminal. Existing 45s timeout retained; active REST checks at 10/20/30/40 seconds only, with the general polling loop suppressed during active requests. Parent fallback executor is bounded.
- Cloudflare Worker verifies RS256 Firebase JWT (fixed project audience/issuer, expiry/auth-time and exact configured Parent UID). Reject arbitrary devices/tokens/fields/body sizes. Read only child-01 token/owner/current durable command from Firestore using server-only OAuth service account. SQLite Durable Object serializes state/rate limit (12 calls/minute), persistent alarms, expiry and at most eight attempts. Check late token rotation without resending the same accepted token; invalid token is neither repeatedly sent nor blindly cleared. Record FCM acceptance before diagnostics; a failed diagnostic write does not resend the same token. Crash between external send and durable store remains at-least-once.
- Rules renderer requires two explicit verified UIDs, prevents strangers/Parent replacing token, prevents clients forging backend dispatch diagnostics, guards command/token monotonic updates, and preserves Child event writes/Parent reads/reminders. Review/publish is separate; no production rules changed. v2210 Cloud Function remains historical source; v2211 deploy does not use it or enable Blaze.
- All requested survival fields plus original/delivered priority, notification channel state and Child token-owner UID are rendered in Parent Health from Firestore and REST. Parent shows its exact runtime UID for provisioning. Public APIs only. unusedAppRestricted is explicitly auto-reset/hibernation-feature enabled status, nullable on unknown/error; it is not proof of current hibernation. Samsung Sleeping/Deep Sleeping membership cannot be queried by documented public APIs.

## Cơ chế phục hồi

Android owns sticky restart, post-unlock boot/package broadcast and persisted watchdog jobs; Google Play services owns same-ID NEVER_EXPIRE 250m EXIT geofence; Cloudflare owns the durable wake job and FCM HTTP v1 dispatch. Legal service creation restores SQLite checkpoint, Firebase auth/listener, GPS, local/cloud heartbeat, token sync, geofence and route manager, then replays pending rows. Actual execution remains in one app process. These owners provide independent triggers, not an OS bypass.

## Bảo toàn

com.family.parent/com.family.child, child-01, one Parent/Child, Kết Nối, Journey/old rows/offline queue, cellular app-only binding/VPN respect, Parent Health, six-hour reminder gate/cooldown/TTL/channel behavior, silent generic protection notification ID42 vs wake22942 vs reminder77, 220 tips, Child clock, exactly one initial generic prompt and both exact safety sentences; no low-battery UI or multi-device. Existing signer fingerprint required. No production data deletion, billing change or secret provisioning.

## Đã kiểm tra

|Check|Result and actual scope|
|---|---|
|Exact reconstruction/static preservation|PASS; existing v222→v2210 chain plus v2211. Product/version/manifest/local-first/generation/secret-boundary assertions|
|coreSelfCheck/scenarioCheck|PASS; actual Gradle tasks|
|Robolectric|PASS: 8 existing journal/GPS tests + 4 new token concurrency/generation/confirmation and 5000-point bounded-memory trip tests; API28 Android shadows, not a device|
|Worker backend|PASS: 23 Node tests; actual RSA signature verification, invalid/forged JWTs, request/token injection, rate/concurrency/restart, late/invalid token rotation, expiry, bounded retry, receipt, diagnostic failure, updateTime guard and HIGH HTTP-v1 payload|
|Worker bundle|PASS: Wrangler 4.147.0 deploy --dry-run; no deployment|
|Worker runtime|PASS: real local workerd/Miniflare + SQLite Durable Object, public unauthorized denial, durable acceptance and rate state across runtime restart; fixture identities and no real Google calls|
|Firestore rules|PASS: 24 positive/negative assertions on actual Firestore emulator 1.22.0, demo-family-location-wake; no production writes|
|Android release build/full lint|PASS: both APKs assembled; zero lint errors, Child88/Parent14 warnings; no suppressed error/baseline trick|
|Dependency audit|PASS after compatible grpc-js test-dependency override; zero known vulnerabilities at check time. Worker has no runtime npm dependencies|
|Wrangler account inspection|NOT VERIFIED production: whoami returned not authenticated. User explicitly paused production deploy; login not attempted|
|ADB/OEM/install|NOT VERIFIED: adb devices empty, no installed Android emulator; no process/Doze/OEM/runtime-radio or update acceptance|
|Signed APK/update|NOT VERIFIED: existing private signer unavailable; unsigned candidate cannot update installed apps|

Evidence logs/reports, APK package/version/alignment checks and SHA-256 are in the candidate delivery ZIP. CI evidence, if completed, is separately recorded in VALIDATION-EVIDENCE.json. Emulator rules validation is not Android emulator/ADB validation.

## Chưa xác minh

Actual Firebase plan state/admin access, real Parent/Child UIDs, Cloudflare account/URL/secret provisioning and deployment, production Firestore rules and IAM, FCM arrival/delivered HIGH or NORMAL, real service/GPS/ACK E2E, token SDK rotation on handset, process reclaim/sticky/Doze/standby/reboot/package replace, SIM/DNS/Firestore socket/VPN behavior, signature/install/update and Samsung 24–48h screen-off/movement/endurance. A–J physical acceptance is not inferred from build or fixtures.

## Rủi ro còn lại

Unsigned/unconfigured candidate needs the existing signer, exact public Worker origin and two measured UIDs. Do not uninstall/clear data to obtain a new UID. Free quotas can reject Worker/SQLite/Firestore operations; Spark is not an unlimited telemetry service. Worker retry/receipt checks stop after eight attempts or TTL; FCM acceptance does not guarantee delivery. A send/store crash can duplicate FCM; Child idempotency is required. Manual JWT verification does not inspect Firebase token revocation; valid ID-token lifetime remains the standard auth window. Secure Firestore rules are a required production prerequisite, not deployed here. OS/OEM can suppress independent triggers; force stop/permission revocation/location OFF cannot be bypassed. Unobserved GPS cannot be backfilled. Disk exhaustion prevents new writes; SQLite FULL transaction latency/battery needs real measurements. Retained journal identity keys grow; Parent's existing 5000-event display window remains. Server-only stale diagnostics cannot prove the latest offline local cause.

## Release

Family-Parent-v2.2.11-unsigned.apk, Family-Child-v2.2.11-unsigned.apk, source/candidate ZIP, SHA256SUMS and evidence. No Installable.apk or production readiness claim. The full step-by-step Cloudflare login/URL/UID/secrets guide is included, and deployment stays paused until the user's next instruction.
