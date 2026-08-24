# Family Location v2.2.4

PATCH release focused on offline journey reliability.

## Added
- Child records network-offline and network-restored journal events locally.
- Child reports sync state, pending event count, last offline interval and last completed catch-up sync.
- Child event upload can fall back to authenticated Firestore REST when Firestore SDK event writes stall/fail.
- Parent can read the selected day's event journal through authenticated Firestore REST when the realtime event stream is cached/stale.
- Parent merges Firebase and HTTPS event results idempotently and shows an offline/sync status card in Journey.

## Changed
- Location samples prefer trusted `Location.time` for short delayed/batched Android fixes instead of always stamping processing time.
- Network-loss recording is debounced to avoid false offline intervals during Wi-Fi/cellular handoff.
- Journey history is not reported as fully synchronized while the Child still reports pending events.

## Preserved
- One Parent + one Child (`child-01`).
- Existing GPS accuracy/stale/jump filtering.
- Existing visit/trip engine and journey calculations.
- Six-hour continuous Location-off threshold before Parent reminder button.
- Parent reminder remains manual only.
- v2.2.3 notification permission/channel behavior.
- Eight-hour quiet protection-notification re-show behavior.
- Existing anti-scam tips and protection UI.

## Tested
- v2.2.4 source wiring validation passed in GitHub Actions.
- Existing `coreSelfCheck` and `scenarioCheck` passed.
- Parent and Child release APK compilation passed.
- Locally zipaligned and signed with the same v2.2.1+ release certificate; APK Signature Scheme v2 and v3 verification passed.

## Known / still needs physical verification
- A real two-phone test should verify: long offline drive, network restoration, catch-up completion, Firestore-realtime-blocked/HTTPS-working network, reboot while pending events exist, and historical day retrieval.
- Android/OEM background execution and notification behavior can still vary by device settings.

Do not merge to `master` until physical-device regression testing is accepted.
