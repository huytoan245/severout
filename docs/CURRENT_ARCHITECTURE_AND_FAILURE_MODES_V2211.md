# v2.2.11 audit before code changes — 2026-10-03

Baseline: v2.2.10, 37b021ed892a92ef5fa36eb02365e8de27c140f6. Read reconstructed LocationService (whole lifecycle/transport/diagnostics), watchdog, BootReceiver, RecoveryStarter, FCM service/token worker, geofence manager/receiver, network manager, SQLite/checkpoint/engine, manifests, unused-app probe, notification builder, Parent refresh/ACK/REST/Health code. No AGENTS.md in source. This report precedes implementation.

## Evidence and defects

1. v2210 atomic SQLite journal is already correct: valid raw GPS + lifecycle + checkpoint before device/event upload; deterministic remote identities; v1 additive migration; removal only after transport success. Retain it. Engine still accumulates/copies every trip point in memory despite all history being durable; a long journey causes avoidable memory growth.
2. onCreate performs preference writes, DB restoration, SDK/job/geofence setup before startForeground. Disk/network SDK initialization can consume the foreground promotion deadline. Promote first. Registered GPS callback can stay marked active forever even if provider silently stalls; use a bounded infrequent fresh-fix health check, not frequent polling/repeated registration.
3. Old listener errors can remove a newer listener after route replacement. Overlapping disable/enable completions can restore obsolete listeners. Add generation guards and preserve one callback/listener. Boot bypasses RecoveryStarter and loses consistent prerequisites/reason diagnostics. Route it through the shared entry point. A boot geofence add lacks receiver lifetime; persist retry ownership and use bounded goAsync.
4. Token worker reads a token and then writes/read-backs asynchronously. Rotation can occur in between; an already-running replaced worker's delayed Firestore/REST write can overwrite a newer token. HTTPS 2xx alone proves its own patch, not that the current local token remains the server token. Use a monotonic durable token generation, Firestore transaction + REST updateTime precondition, current-user/local-generation checks and explicit server read-back.
5. Existing battery/standby/unused-app probes use public APIs. Unused-app status describes enabled auto-reset/hibernation capability, not proof the currently executing app is hibernated. Unknown/error must remain unknown. Samsung Sleeping/Deep Sleeping membership has no documented public query; do not infer it. Parent collects only some survival fields and renders almost none of them. Add a full Health snapshot from both Firestore and REST, including numeric original/delivered FCM priority and notification-channel state.
6. FCM already checks delivered HIGH and does not claim NORMAL wakes a dead service. Duplicate still posts a visible wake before durable completion; keep command idempotency and distinguish requested start from service actually running. No private APIs, permanent locks or tight alarms are necessary.
7. Current wake source is a Firebase Cloud Function requiring deployment outside Spark. Parent never explicitly calls an external backend; it relies on the Firestore trigger. Replace the active deployment path with authenticated Cloudflare Worker + FCM HTTP v1; preserve durable Firestore commands. Enqueue a persistent Parent outbox before command write so Parent process interruption can retry the command/wake. Never let an older command overwrite a newer one.
8. Worker must cryptographically verify Firebase ID token audience/issuer/signature/expiry and a single configured Parent UID. It must read child-01 token from Firestore with a server-only service account, verify current request/TTL, serialize idempotency/rate/retry state in one SQLite Durable Object, and use bounded alarms for transient failure/missing token. No arbitrary token/device/project accepted from clients. Server private key only in Cloudflare Secrets; APK contains only a public Worker URL. Missing URL/UID/secrets fail closed and remain NOT VERIFIED.
9. Network failover already serializes callback fields and respects VPN. Healthy 60s / degraded 15s probes, actual-operation hints, lost-cell checks and cloud restart remain. Parent REST uses an unbounded single queue; bound concurrency so command/wake progress does not sit indefinitely behind reads.
10. Immediate watchdog is expedited on every API although the Worker supplies no ForegroundInfo. On API26–30 WorkManager's expedited compatibility path requires it; use regular persisted work there and expedited only on API31+, with a bounded retry count.

## Minimal scope

Append v2211 to the existing reconstruction chain. v2.2.11/code33; branch v2211-samsung-survival-and-wake; new Draft PR based on v2210; no master merge. Preserve all UI text, one initial prompt, packages, IDs/channels/reminders, 220 tips and old data/signing identity. Implement the defects above and tests; do not rewrite the app.

## Recovery chain and independent ownership

|Situation|Trigger -> service -> auth/listener -> GPS -> network -> pending upload|
|---|---|
|Service killed / process reclaimed|Android sticky restart; OS-persisted watchdog; Play-services EXIT; externally sent FCM HIGH -> shared permitted FGS start -> DB checkpoint/auth retry/single listener -> one tracking callback/fresh fix -> serialized route manager -> deterministic pending upload|
|Doze / standby|FCM HIGH or permitted Play-services/OS event; scheduled jobs can defer -> same restoration chain; no latency guarantee|
|Samsung sleep/deep sleep / Android restricted|Documented diagnostics + configured user exemption; OEM can suppress every trigger -> resume only when Android permits -> same chain; no private bypass|
|Reboot / APK replace|After unlock broadcast -> persistent jobs + same-ID geofence re-registration + allowed FGS start -> restore DB/auth/listener/GPS/network -> replay pending|
|Wi-Fi lost / Firebase path broken|Default network callback or actual server failure -> service already alive -> auth/listener generation reset -> GPS still journals -> default SIM or app-only cellular binding -> upload when authoritative server succeeds|
|Both radios offline|GPS callback -> validated atomic local journal; cloud attempts bounded -> retain pending; service/jobs/GMS may independently remain scheduled|
|Network returns hours later|Network callback/heartbeat/watchdog -> service restore if needed -> auth/listener -> GPS -> route recovery -> pending replay with original GPS timestamp|
|Parent request after reclaim|Durable Parent outbox -> durable Firestore command -> authenticated Worker -> Durable Object bounded retry -> FCM -> Child delivered-priority check -> lawful FGS -> durable ACK/fresh GPS/result -> Parent stages/45s timeout|

All app execution still runs in one process. Independent scheduling ownership is Android, Google Play services and Cloudflare; this is not multiple immortal app processes. Force Stop, permission revocation, location OFF and OEM execution denial cannot be bypassed. Unobserved GPS while execution is withheld cannot be reconstructed.

## Primary references checked

- https://developers.cloudflare.com/durable-objects/platform/pricing/ — Workers Free supports SQLite-backed Durable Objects; limits fail operations when exhausted.
- https://developers.cloudflare.com/durable-objects/api/alarms/ — persistent alarms, at-least-once execution, bounded application retry required.
- https://firebase.google.com/docs/auth/admin/verify-id-tokens — Firebase JWT validation requirements.
- https://firebase.google.com/docs/cloud-messaging/send/v1-api — server OAuth/service account for FCM HTTP v1.
- https://firebase.google.com/pricing — FCM no-cost; retain Firestore Spark quotas; no Cloud Function deploy.
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start — delivered priority/exemptions do not bypass permissions or all background restrictions.

Production account access, Worker URL/Parent UID, existing signer and physical Samsung/ADB evidence are not currently available. Source/build tests cannot establish production delivery or endurance.
