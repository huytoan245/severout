# v2.3.1/code35 — bootstrap validation, 2026-10-05

STOPPED BEFORE SIGNING/PRODUCTION DEPLOYMENT FOR REVIEW. No phone installation/uninstall, release-key creation/change, production provisioning, deploy or master merge. Private capability fixtures were generated for LOCAL TESTS ONLY, never provisioned to a cloud project, never committed/printed, and are removed after verification.

| Actual local check | Result |
|---|---|
| Worker JWT/enrollment/bootstrap/CAS/replay/recovery/wake | 58/58 PASS |
| Fresh Wrangler dry-run + actual workerd with two SQLite DO classes | PASS; Google/JWKS/OAuth/Firestore/FCM intercepted |
| Firestore emulator Rules + real REST atomic commits | 59/59 PASS; localhost demo-family-location-wake only |
| Child journal/token/inbox Robolectric | 26/26 PASS |
| Enrollment proof/journal/bootstrap Robolectric/JVM | 11/11 PASS |
| Core self check and scenario | PASS |
| Parent/Child release build | PASS, UNSIGNED and UNPROVISIONED review pair |
| Full release lint | 0 errors; warning counts recorded in AUTOMATED-GATE.json |
| Password-free signing regression | 73/73 PASS; mocked certificate/sign control flow, no real signer |
| Bootstrap build/sign boundary regression | 12/12 PASS on Windows; 10 portable cases in Linux CI |
| Protected generator + offline hash-only proposal | PASS with fresh ephemeral fixture; no cloud application |
| Actual private capability injection in both release APKs | PASS; protected external build, each role's DEX hash matched, opposite role absent |
| Actual private unsigned signing preflight | PASS: packages/version/code, byte hashes, integrity, 16KiB zipalign, capability hashes/expiry; NO password read or signer inspection |
| Actual unprovisioned review preflight | EXPECTED REJECTION for missing provisioning, BEFORE password/signing |
| Secret boundary | PASS: neither temporary capability appears in tracked source or public review logs |
| Existing release JKS | Byte SHA-256 unchanged: 71a8de8f085e05d0a229fb2da0cd6ec95e75b6a7592a4e986b8ea1aafefc4ddb |

The pinned CERTIFICATE SHA-256 stays 62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f. The keystore FILE hash above is a different value. No certificate export/password entry/signing was performed in this task.

Actual unprovisioned review APK SHA-256 (not Installable/signature evidence):

```text
dfcd48f1d496476a3e2b9429eb8f228cdcad4631ed67080bdad0df237e4a3e66  Family-Parent-v2.3.1-unsigned.apk
b6c40cf0a9ba8770c2aed7541c60410e30dad80f79dcde925db5025035d5baf2  Family-Child-v2.3.1-unsigned.apk
```

A–O are mapped individually in ZERO_SETUP_PAIRING_V231.md. Extra recovery coverage confirms that recovering Parent before pairing preserves the still-empty Child's valid capability while revoking only recovered-role hashes. New/retired hash reuse is rejected.

## Commands actually executed

```text
node --test cloudflare-wake/test/*.test.js
node test/runtime-smoke.mjs (fresh Wrangler deploy --dry-run ONLY)
node render-rules.mjs --output .wrangler/test-fixture.rules
node test/firestore-rules.mjs (localhost:8086 demo project only)
pwsh -NoProfile -File scripts/test-release-signing.ps1 -JavaHome <JDK17>
pwsh -NoProfile -File scripts/test-bootstrap.ps1
python -X utf8 scripts/validate.py
python -X utf8 scripts/reconstruct.py
gradle captureSigningBuildTools :core:coreSelfCheck :core:scenarioCheck
  :enrollment:testDebugUnitTest :child-app:testDebugUnitTest
  :parent-app:assembleRelease :child-app:assembleRelease
  :enrollment:lintRelease :parent-app:lintRelease :child-app:lintRelease
scripts/new-bootstrap-config.ps1 (protected external ephemeral test directory only)
node bootstrap-plan.mjs --hashes <fixture-public-hashes> --output <offline-fixture-plan>
scripts/build-private-bootstrap.ps1 (external ephemeral build, --no-daemon/no-cache)
scripts/sign-release.ps1 -Version 2.3.1 -CheckEnvironmentOnly (private fixture PASS, public review REJECT)
```

Native Windows Gradle receives ASCII build/init-script paths because the workspace path contains Vietnamese characters. The official Firestore emulator v1.22.0 JAR ran directly on Studio JBR25 against localhost with a demo project and no production fallback. Android tests use Robolectric sdk35 on JDK17; the target remains Android36. Initial build/test errors were corrected before the successful runs above. Emulator PATCH query precondition behavior motivated use of the documented conditional REST commit Write for enrollment; the actual emulator now verifies that wire format and stale-CAS failure.

Public evidence: out/v231/AUTOMATED-GATE.json contains tested input hashes, backend/script hashes, unit/lint counts and command logs. Private test evidence retains sanitized logs only; no capability-bearing APK is a delivery artifact. The source/review ZIP is unsigned and unprovisioned, never a release ZIP.

## CI and limitations

CI runs on Draft PR #27, base v230-clean-reliable-baseline. It receives EMPTY bootstrap variables, rejects secret injection, tests both roles with random in-memory values, and uploads unprovisioned review APKs/test reports only. Current-head CI status is captured separately after the run; prior-head PASS is not asserted as the new result.

Not tested: fresh installation/pairing on two real phones; real Android Keystore generation/hardware backing; permission UX after this change; actual update/reboot/process death/clear-data recovery; production FCM, ACK, GPS or journey; Firebase Spark/Workers quota/latency and Samsung endurance/OEM/force-stop behavior. No hardware attestation is implemented. Before enrollment a leaked APK can expose its role capability and race the owner; after consume, that token cannot reclaim. Signing/apksigner verification for v231 and a SIGNED Release ZIP SHA-256 are NOT PERFORMED, deliberately stopped for review.
