# Family Location release signer v2.3.0 onward

Pinned signer SHA-256: `NOT_CREATED`

Status: existing keystore creation and manual keytool inspection reported PASS by user on 05/10/2026. Automated read-only recovery/pinning awaits direct local password input. NOT_CREATED above describes the missing automated pin, not a missing keystore. Parent and Child must use the SAME existing certificate. No Installable APK or signed Release ZIP exists until both signing gates complete.

Expected public certificate SHA-256 (user-confirmed): `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`. This is the sole allowed v230 signer. Do not substitute a new key to resolve an inspection/password failure.

Keystore standard: `C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks` (outside repository). Alias `family-location-release-2026`. Store/password never committed. Back up the keystore and password separately using your local secure process; losing them prevents future updates signed by this identity.

The original creator generated the keystore successfully, then failed because PowerShell split the unquoted dotted JVM argument `-J-Duser.language=en`: keytool received `.language=en` and returned exit 1. This was reproduced with `-help`, without reading the keystore. ProcessStartInfo.ArgumentList now passes each JVM option intact and captures native stderr independently of exit status (ordinary JKS/export warnings are not errors). The old creator entry point now delegates to recovery ONLY; there is no generation, overwrite or alias-change operation.

Use PowerShell 7.2+ to run scripts/recover-release-signer.ps1. It verifies the existing file outside the repo, exact `PrivateKeyEntry` alias, keytool exit codes, and SHA-256 of the exported public DER certificate. It compares with the user-confirmed fingerprint before changing the pin, refuses a different existing pin, and checks that keystore bytes are unchanged. The exported temporary public certificate is removed. It reads the password directly via Read-Host -AsSecureString, or an existing local FAMILY_LOCATION_KS_PASSWORD environment value. No password in command argument values, logs or files. Never paste passwords into chat.

Local recovery plus paired signing, using the same password prompt/session:

```powershell
Set-Location -LiteralPath 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\source'
.\scripts\recover-release-signer.ps1 -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -SignAfterPin -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk'
```

Then scripts/sign-release.ps1 requires the exact pin AND independently inspects the existing certificate again. It checks tested Android input hashes/unsigned APK hashes, exact version2.3.0/code34/package names before and after signing, sole certificate match, successful apksigner verify and16KiB zipalign for BOTH apps. It stages both APKs, checks the release ZIP/manifest, and reruns independent validation before promoting either Installable file. A future signer mismatch is rejected. Scripts restore the prior password environment value in finally; newly prompted input is removed after use. Signing is not Samsung/runtime/E2E verification.

Outputs under out/v230: Family-Parent-v2.3.0-Installable.apk, Family-Child-v2.3.0-Installable.apk, Family-Location-v2.3.0-Release.zip, SIGNED-SHA256SUMS.txt (the two APKs), RELEASE-SHA256.txt (ZIP hash), public signature logs and SIGNING-VALIDATION.json. The ZIP contains the APK pair, APK hash manifest, pinned signing document and both public verification logs. A ZIP cannot include its own final hash; that hash is recorded separately.

Independent post-sign regression, no password required:

```powershell
.\scripts\validate-signed-release.ps1 -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk'
```

Do not uninstall old apps until both release APKs build, new signer, zipalign and signature verification PASS. Clean install intentionally changes Firebase anonymous UIDs; preserve cloud events/project. Read new Parent runtime UID and Child token-owner UID after install, then review exact-UID rules/secrets. Production remains paused.
