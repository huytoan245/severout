# Family Location release signer v2.3.0 onward

Pinned signer SHA-256: `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`

Status: existing signer verified locally and pinned. APK signing/verification is a separate gate.

Current state05/10/2026: user ran actual recovery and reported the verified/pinned fingerprint above. Keystore and alias are preserved; user reports old apps already uninstalled. Signing has not completed. Codex will not install apps, change signer or deploy production. New signing preflight PASS checks actual tools and both unsigned APKs without reading a password. Final signing requires direct local password input.

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

Build-tools resolver order: optional -BuildToolsVersion, then evaluated AGP/project version, then highest complete installed stable version. Preview directories, preview/mismatched package metadata and missing required files are rejected/skipped. Each failure identifies its missing tool and exact expected path; selected version/aapt2/zipalign/apksigner/Java paths are printed. Captured ANDROID-BUILD-TOOLS.json is produced by capture-build-tools.gradle and accepted only if the Android build-file hashes match. Current actual Gradle evaluation reports36.0.0 for BOTH modules, and the SDK has all required files at that version. No SDK download is needed.

SDK apksigner.bat wrappers resolve to a verified existing JAR in lib, build-tools root or adjacent framework; the JAR is invoked through the explicitly provided Java. A batch file without its underlying JAR is incomplete. Passwords never pass through cmd.exe argument parsing.

Windows zipalign was reproduced failing to open the unchanged APK under the Vietnamese project path, while the same SHA-256 APK at an ASCII path passed. Native APK operations now use a byte-identical temporary ASCII copy if necessary, remove that copy nonrecursively in finally and preserve the original. Every APK ZIP entry is read/decompressed; signed APK integrity additionally requires successful cryptographic verification.

Environment-only gate, no password/keystore inspection/signing:

```powershell
.\scripts\sign-release.ps1 -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' -CheckEnvironmentOnly
```

Since the existing signer is already pinned, direct signing is sufficient and rechecks its actual certificate:

```powershell
.\scripts\sign-release.ps1 -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk'
```

Outputs under out/v230: Family-Parent-v2.3.0-Installable.apk, Family-Child-v2.3.0-Installable.apk, Family-Location-v2.3.0-Release.zip, SIGNED-SHA256SUMS.txt (the two APKs), RELEASE-SHA256.txt (ZIP hash), public signature logs, RELEASE-INFO.json and SIGNING-VALIDATION.json. The ZIP contains the APK pair, APK hash manifest, pinned signing document, both public verification logs and version/package/certificate/build-tools/hash metadata. A ZIP cannot include its own final hash; that hash is recorded separately.

Independent post-sign regression, no password required:

```powershell
.\scripts\validate-signed-release.ps1 -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk'
```

User reports old apps already uninstalled; no further uninstall or automatic installation is authorized. Clean install intentionally changes Firebase anonymous UIDs; preserve cloud events/project. After BOTH signed APKs pass, stop for user installation, legitimate permissions and exact new Parent runtime UID/Child token-owner UID collection. Only then review production rules/secrets/deployment. Production remains paused.
