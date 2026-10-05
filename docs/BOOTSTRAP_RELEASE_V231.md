# Local private bootstrap preparation — v2.3.1/code35

CURRENT STOP: do not execute release signing or deploy production until this change has been reviewed. The following is the future local preparation procedure; no production capability has been provisioned by Codex. End users still install/open and grant Android permissions only.

All commands run in PowerShell 7 on this same Windows host. Never print the private JSON, Get-ChildItem Env:, token variables or generated BuildConfig; never paste them into chat. Do not put the directory under the repository or a synced/shared folder. The generator creates a new protected directory, with only the current Windows SID and SYSTEM permissions, and refuses overwrite. Private config and compiled APK/build directories are sensitive before enrollment; public reports contain hashes only.

## 1. Generate once outside Git

```powershell
$repo = 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\source'
$private = 'C:\Users\Admin\Documents\FamilyLocation-Bootstrap\v231-initial'
& "$repo\scripts\new-bootstrap-config.ps1" -Directory $private
```

This generates two distinct random 32-byte values with seven-day expiry, writes bootstrap.private.json and the hash-only BOOTSTRAP-PROVISIONING.json. It neither reads nor changes the release keystore. An existing directory causes a stop; use a new directory for an explicitly reviewed recovery. Expired provisioning must be replaced with newly generated capabilities, not extended to authorize an old leaked value.

## 2. Prepare hash-only server proposal, OFFLINE

```powershell
Push-Location $repo
try {
  node .\cloudflare-wake\bootstrap-plan.mjs --hashes "$private\BOOTSTRAP-PROVISIONING.json" --output "$private\initial-provisioning-review.json"
} finally { Pop-Location }
```

The proposal is a create-only conditional commit to the fixed Firestore family doc, contains hashes/expiry only and no UID. It has no network/apply option. If the family already exists, use separately reviewed recovery/migration; do not overwrite it or clear existing phones. Applying this proposal and deploying the compatible Worker/Rules is a later operator step needing review. Backend infrastructure secrets remain server-side. No Firebase Blaze is introduced. Neither plaintext capability is a Wrangler/Google admin secret.

## 3. Prepare unsigned PRIVATE role APKs locally

After the reviewed source gate has been run, use its evidence in out/v231. The helper checks tested source hashes, config scope/expiry and external path, creates a fresh protected ASCII build directory, copies canonical tracked appsrc, and sets ONLY process environment FAMILY_LOCATION_PARENT_BOOTSTRAP / FAMILY_LOCATION_CHILD_BOOTSTRAP. It restores the environment afterwards; no capability is a command argument.

```powershell
& "$repo\scripts\build-private-bootstrap.ps1" `
  -Config "$private\bootstrap.private.json" `
  -BuildDirectory 'C:\Users\Admin\.codex\tools\family-location\private-v231-initial' `
  -EvidenceDirectory "$repo\out\v231" `
  -Gradle 'C:\Users\Admin\.codex\tools\family-location\gradle-9.5.0\bin\gradle.bat' `
  -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' `
  -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' `
  -Python 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
```

The helper runs core/scenario/unit tests, builds unsigned release APKs and lint, disables Gradle build/configuration caches and daemon reuse, redacts both values from its local build log, collects evidence outside Git, and scans actual DEX contents by SHA-256. Parent must contain its own capability and never Child's; Child must contain its own and never Parent's. No token is emitted by the scan. Debug always uses an empty value. CI rejects attempted secret injection and produces unprovisioned review APKs only.

## 4. Password-free preflight only, THEN STOP

```powershell
& "$repo\scripts\sign-release.ps1" -Version 2.3.1 `
  -UnsignedDirectory 'C:\Users\Admin\.codex\tools\family-location\private-v231-initial\candidate' `
  -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' `
  -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' `
  -BuildToolsVersion 36.0.0 -CheckEnvironmentOnly
```

This checks tested inputs, APK hashes/package/version/archive/zipalign, scope/expiry and each DEX capability hash before any password prompt or signer access. Running it on CI/unprovisioned review outputs MUST FAIL with missing local provisioning evidence. Do not remove this gate or supply a fabricated manifest to bypass it.

STOP for review before removing CheckEnvironmentOnly, provisioning cloud state, deployment or distributing/installing APKs. Future signing must use the SAME JKS/alias/certificate, enter the password locally via SecureString, and require both Parent/Child signature+zipalign checks before promotion. No signer regeneration, uninstall or master merge is part of this workflow.

After enrollment the server consumes each hash atomically. Keep private artifacts private until both roles are enrolled; after consumption their embedded tokens cannot reclaim roles. Full uninstall/clear data requires fresh-capability operator recovery as described in ZERO_SETUP_PAIRING_V231.md. The exact same committed proof can retry within the nonce lifetime; new proofs carrying consumed tokens are rejected.
