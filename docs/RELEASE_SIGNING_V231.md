# Family Location v2.3.1 release signing

Pinned signer SHA-256: `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`

This is the existing, manually inspected and v2.3.0 APK-verified release signer.
No new release key is created. Keystore and passwords are excluded from Git.

- Keystore: `C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks`
- Alias: `family-location-release-2026`
- Parent: `com.family.parent`, versionName `2.3.1`, versionCode `35`.
- Child: `com.family.child`, versionName `2.3.1`, versionCode `35`.

Android enrollment keys are per-installation Android Keystore keys. They are
independent of this release JKS/certificate and do not change the APK signer.

Signing is PENDING a local password entry. Unsigned candidates are never named
Installable. `scripts/sign-release.ps1 -Version 2.3.1` verifies input hashes,
version/package, archive integrity, zipalign, and both APK signatures against
this same pin before publishing either Installable APK or the release ZIP.
It prompts with SecureString only in local PowerShell. Never send passwords in
chat, command arguments, files, logs, or Git.

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Users\Admin\Documents\ChatGPT\Xác định Vị trí\source\scripts\sign-release.ps1' -Version 2.3.1 -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' -BuildToolsVersion 36.0.0
```

Outputs after BOTH APK gates PASS: `out/v231/Family-Parent-v2.3.1-Installable.apk`,
`Family-Child-v2.3.1-Installable.apk`, `Family-Location-v2.3.1-Release.zip`,
`SIGNED-SHA256SUMS.txt`, signing evidence and ZIP checksum.

No installation, uninstall, production deployment or merge is performed by this gate.
