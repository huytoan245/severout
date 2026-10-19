# v2.3.2/code36 — Reliable GPS & Journey

Status: **READY FOR DEVICE TEST**, not FULLY VERIFIED. No Worker/Rules deployment, production mapping write, phone install/uninstall, bootstrap creation or signer change is part of this change.

## Confirmed diagnosis (read-only, 19 October 2026, Asia/Saigon)

Production is already schema232 and both roles are enrolled. The latest request at 08:37:18 has Worker acceptance at 08:37:20, dispatch at 08:37:21 and matching FCM receipt at 08:37:21. Service/ACK/locating/completion markers belong to an older request; **SERVICE_STARTED for the latest request is UNKNOWN**. The `ok` result must not be joined to the latest request. Latest raw GPS and heartbeat are from 17 October, about 06:37. Callback telemetry is older and does not cover all old acquisition paths.

Background location and notification permissions are reported false; fine location is true. The existing recovery policy blocks a new background location service without that permission. This is a confirmed prerequisite issue, not proof of the exact exception for this request. `fcmWakeStartResult=failed` lacks matching request evidence. No recent separate diagnostics documents exist. Do not infer a Samsung kill, network failure or GPS failure from missing evidence. Private raw evidence stays outside Git; this document contains no UID, token, coordinate or secret.

Confirmed source defects: Firestore and HTTPS callbacks raced in ChildDeviceWriter; GPS lacked an independent no-callback watchdog; Parent ended waiting at 45 seconds; three history paths silently capped records at 5,000; direct route rendering bridged gaps/noise. Source baseline is 530f27a; this branch retains the existing 92a85ec schema232 enrollment implementation because production is already using it. PR27/28 and historical artifacts stay intact.

## Changes

- Durable request ID/stage journal: receipt differs from service ACK; GPS has cancellation, 30-second watchdog, at most one retry, explicit error codes and stale-callback leases. Terminal data remains durable until an authoritative write succeeds.
- Firestore primary, one bounded CAS HTTPS fallback, exactly-once completion. Stale commands/fixes cannot regress a newer command/location. A committed retry or a newer authoritative GPS can satisfy confirmation without overwriting newer coordinates. SERVER_CONFIRMED requires the current request and server-persisted fresh coordinates.
- Adaptive tracking targets 30 seconds moving / 120 seconds stationary, with 30-second minimum callbacks. Every accepted distinct raw fix and engine checkpoint commit atomically before upload, independently of trip confirmation. Stable event IDs, SQLite v2 rows, offline queue and retry semantics remain; gaps are explicit, not interpolated.
- Parent uses daily queries and `(time, document ID)` cursors, deduplicates by document ID and carries lifecycle context across midnight. A daily realtime listener has no total-record cap; HTTPS pages all records. Statistics use every validated sample and exclude jitter, implausible jumps and gaps. Only rendering is downsampled; lines stop at gaps. Unobserved stop durations are labelled uncertain.
- Parent Health shows valid GPS/callback/rejection/upload/pending/request-stage evidence and distinguishes stale GPS from online heartbeat. No UID/token is displayed by the diagnostic field list. Existing Child UI/protection text, Parent name “Kết Nối”, 220 tips, failover, reminders and family/device IDs remain.

## Safe update build/sign

`-PupdateInPlace=true` creates **UPDATE_IN_PLACE** APKs without bootstrap. Their manifest requires the existing UID and installation key; runtime never creates/resets Auth/key or invokes rebind. If identity is missing/mismatched, it fails closed. Fresh-install/recovery builds and their protected bootstrap signing gate remain separate. Existing SharedPreferences, key alias and `location.db` version2 are retained; no data-destructive migration is used.

Build/test/lint with `gradle -PupdateInPlace=true ...`. Collect with `scripts/prepare-update-candidate.py --build-root <tested appsrc> --evidence-root <test logs> --output-directory <new update directory>`. The collector checks source/build hashes, tests, lint, manifest runtime mode and empty BuildConfig bootstrap fields. CI uploads this same tested update mode.

Sign only through the existing paired gate with **`-Version 2.3.2 -UpdateInPlace`**, Android build-tools36.0.0 and the existing JKS/alias. The explicit update gate verifies both APK hashes and their manifest mode before the password prompt; it does not bypass package/version/certificate/apksigner/16KiB alignment gates. Use PowerShell7 and enter SecureString locally; never send a password to chat. No actual signing occurs in CI. Certificate remains `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`.

Install as an in-place update with the same package/certificate, never uninstall or Clear Data. Code36 is retained as requested; this is not a downgrade from the known code35/36 baseline. Updating cannot remotely grant Android permissions or guarantee an OEM allows service start. The remote Child continues running its installed APK until an authorized update can be performed.

## Validation and remaining work

Tests cover receipt without service, missing/null GPS/deadline, late/out-of-order callbacks, Firestore failure/HTTPS completion, confirmation after45 seconds, terminal idempotency, short movement/stops, durable SQLite queue beyond6,000 rows, checkpoint/reopen/migration, midnight visits and >7,000 history records. Actual Firestore emulator paging covers6,500 same-day +900 next-day rows, equal timestamps and role/stranger authorization. Workerd smoke intercepts Google traffic; no real FCM delivery. Mock signing tests exercise update and fresh/recovery boundaries, not a real release key.

When the Child is accessible: update both apps without deleting data; verify permissions/notification settings, real request stages/GPS/ACK, offline hours/reconnect, process death/reboot, network switch, midnight history and Samsung24–48h. Until that evidence exists, retain READY FOR DEVICE TEST.

References: [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [current location request](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest), [Firestore cursors](https://firebase.google.com/docs/firestore/query-data/query-cursors).
