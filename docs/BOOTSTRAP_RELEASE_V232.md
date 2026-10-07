# Private unsigned v232 preparation

Initial capabilities have no expiry. They are generated once per role, consumed/revoked forever after first claim; reinstall uses the separate stable binding. Keep the private config outside Git under a protected Windows ACL. Do not regenerate the existing v231 config, release JKS or alias.

`new-bootstrap-config-v232.ps1 -Directory <new-protected-absolute-directory>` creates two random 32-byte capabilities, hash-only BOOTSTRAP-PROVISIONING.json, and bootstrap.private.json. It refuses an existing directory. Never print/open the private file in chat or CI. `migration-plan-v232.mjs` consumes only the public manifest and protected admin snapshot to emit an offline CAS proposal; it performs no cloud calls.

After all actual source/Worker/workerd/Rules/signing-regression/Android/lint gates pass, `build-private-bootstrap-v232.ps1` validates the recorded source hashes, creates a fresh external ASCII build directory with protected ACL, injects each capability into its own release BuildConfig via process environment, redacts logs, repeats Android tests/lint/build, collects unsigned APKs and verifies actual DEX role separation by SHA-256. Debug/CI builds forbid injection and public review APKs remain unprovisioned. Neither pepper nor Google server credentials are read by this build.

Unsigned names: Family-Parent-v2.3.2-unsigned.apk and Family-Child-v2.3.2-unsigned.apk. The hash-only manifest and source/build evidence accompany the protected candidates; bootstrap.private.json is never packaged. Source/review ZIPs contain public unprovisioned APKs only. Private APKs necessarily contain their own plaintext initial capability and must not enter public Git/CI artifacts.

Password-free preflight after preparation:

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File '<source>\scripts\sign-release.ps1' -Version 2.3.2 -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -UnsignedDirectory '<protected-private-build>\candidate' -BuildToolsVersion 36.0.0 -CheckEnvironmentOnly
```

Preflight reads no password, inspects no JKS certificate and publishes no Installable. Production migration and stable pepper deployment must be reviewed and verified before a future actual signing invocation. At that later stage remove `-CheckEnvironmentOnly` and enter SecureString locally. Do not run actual signing now; no password is requested by this review candidate.
