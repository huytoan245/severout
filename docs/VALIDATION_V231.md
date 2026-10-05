# v2.3.1/code35: actual local validation, 2026-10-05

Production is stopped for design review. No production Google/Cloudflare writes,
no app install/uninstall, no release-key creation/change, no master merge.

| Check actually run | Result |
|---|---|
| Worker JWT/enrollment/CAS/replay/recovery/wake tests | 45/45 PASS |
| Fresh Wrangler dry-run + actual workerd, two SQLite DO classes | PASS |
| Public JWT -> enrollment/token -> HIGH FCM fixture dispatch, runtime restarts | PASS; all outbound Google traffic intercepted |
| Firestore emulator Rules + actual REST commit assertions | 51/51 PASS; localhost demo project only |
| Retained Child Robolectric journal/token/inbox tests | 26/26 PASS |
| Enrollment proof/DER conversion/cold-client journal tests | 7/7 PASS |
| Core self check and trip/reboot/gap scenario | PASS |
| Parent/Child release builds | PASS; unsigned |
| Password-free v2.3.1 signing preflight | PASS; com.family.parent / com.family.child, 2.3.1/code35, input/APK hashes, archive integrity, 16KiB zipalign |
| Full release lint, including enrollment library | 0 errors; Parent18, Child102, Enrollment3 warnings |
| Password-free signing regressions | 69/69 PASS; fixture control flow, not real v2.3.1 signatures |
| Existing signed v2.3.0 independent release validator | PASS; original APK/ZIP hashes retained |

Enrollment lint warnings concern deliberate background-thread SharedPreferences
commit-before-POST durability and newer SDK/Firebase versions. Dependencies
remain at the tested baseline versions. Existing app lint warnings are retained;
the report does not claim zero warnings.

Actual Android command (ASCII build mirror because native Windows/Gradle tools
cannot reliably read the workspace's Vietnamese path):

```text
gradle -I capture-build-tools-v231.gradle captureSigningBuildTools
  -PfamilySigningMetadata=<ASCII-evidence>/v231-ANDROID-BUILD-TOOLS.json
  :core:coreSelfCheck :core:scenarioCheck
  :enrollment:testDebugUnitTest :child-app:testDebugUnitTest
  :parent-app:assembleRelease :child-app:assembleRelease
  :enrollment:lintRelease :parent-app:lintRelease :child-app:lintRelease
```

The collector compares byte hashes of all build inputs with canonical source,
checks actual test XML/full lint reports and successful command logs, and copies
the tested APK pair into `out/v231`. The signing preflight independently checks
these input/APK hashes, aapt2 identity and 16KiB zipalign without reading a password.
`AUTOMATED-GATE.json`, actual logs and `SHA256SUMS.txt` accompany the local candidate.

Actual unsigned candidate SHA-256 (these are NOT signed Installable hashes):

```text
43807abd27eb0bdc244929cf83e99755cbe6fcfeeb44b6a610cf6945633a723d  Family-Parent-v2.3.1-unsigned.apk
bbeb4a08850415963d734a42af7ad8d36d98e98415952bf41ffddb3689653fcc  Family-Child-v2.3.1-unsigned.apk
```

Worker/rules commands actually run:

```text
node --test test/*.test.js
node --check src/worker.js
node test/runtime-smoke.mjs   # fresh Wrangler --dry-run; never production
node render-rules.mjs --output .wrangler/test-fixture.rules
node test/firestore-rules.mjs # against localhost:8086 demo-family-location-wake
pwsh -NoProfile -File scripts/test-release-signing.ps1 -JavaHome <JDK17>
python -X utf8 scripts/validate.py
pwsh -NoProfile -File scripts/validate-signed-release.ps1 -Version 2.3.0 ...
```

Local Firebase CLI cache had a missing transitive module; the existing official
Firestore emulator v1.22.0 JAR was started directly with Studio JBR25, localhost
and demo project isolation. Rules and REST tests still executed against the
real emulator. No Firebase production fallback was used.

Required A–O coverage and limits:

| Cases | Actual coverage |
|---|---|
| A/B/C fresh pair, either install order | Registry implementation + real ECDSA model tests; both public roles also run in workerd |
| D/E/F concurrent enrollment/third roles | Concurrent candidates and competing registry-instance CAS; exactly one winner per role |
| G token rotation | Same/lower/equal-different revisions; actual emulator atomic membership/device CAS |
| H backend restart | Real workerd/SQLite nonce/registration/rate restart plus retry/alarm model tests |
| I offline initial enrollment | Fault-injected network outage + eventual enrollment in actual registry implementation |
| J duplicates | Same proof, new nonce/same UID+key, cached response and crash-after-commit retries |
| K app process death | Robolectric cold-client committed proof + backend response-loss/restart; OS kill on a phone unverified |
| L APK update | Retained UID/key model, stable Keystore alias/backup disabled/same release pin; real Android update/reboot unverified |
| M/N unauthorized wake/token | UID/role/key/nonce/epoch tests; real public endpoint wrong UID/project denial; Rules direct token denial |
| O Rules emulator | 51 actual assertions, including SDK reads/commands/history and server REST token CAS |

Not checked: two physical phones, hardware Keystore backing, actual fresh-install
pairing/permission UX, real WorkManager process kill/reboot, production FCM/ACK/GPS,
Spark/Workers quota under real tracking load, Samsung endurance/force-stop/OEM behavior.
The first claimant's ownership is not attested; the bootstrap window must be reviewed.

v2.3.1 actual APK signatures and Release.zip hash remain PENDING LOCAL PASSWORD.
Do not rename unsigned candidates Installable or infer signing success from mock tests.
GitHub CI results are available on the Draft PR; the current-head result is reported
separately after the remote run finishes.
