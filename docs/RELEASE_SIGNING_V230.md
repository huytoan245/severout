# Family Location release signer v2.3.0 onward

Pinned signer SHA-256: `NOT_CREATED`

Status: no keystore/password input available. Never use the old fingerprint or generate a password. Parent and Child must use the SAME new certificate. No Installable APK or signed Release ZIP exists until this gate completes.

Keystore standard: `C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks` (outside repository). Alias `family-location-release-2026`. Store/password never committed. Back up the keystore and password separately using your local secure process; losing them prevents future updates signed by this identity.

After actual build/testing report PASS for automated checks, run scripts/create-release-keystore.ps1 locally with -JavaHome. It reads passwords directly in local PowerShell via Read-Host -AsSecureString, or existing FAMILY_LOCATION_KS_PASSWORD environment input. Never paste passwords into chat. No password in process command arguments, logs or files. Script writes the public fingerprint above only after keytool succeeds and the certificate is verified.

Then scripts/sign-release.ps1 requires that exact pinned fingerprint, version2.3.0/code34/package names, and both APKs passing signature/16KiB zipalign. It stages both before publishing either Installable file. A future signer mismatch is rejected. Script clears temporary password environment input after use. Signing is not Samsung/runtime/E2E verification.

Do not uninstall old apps until both release APKs build, new signer, zipalign and signature verification PASS. Clean install intentionally changes Firebase anonymous UIDs; preserve cloud events/project. Read new Parent runtime UID and Child token-owner UID after install, then review exact-UID rules/secrets. Production remains paused.
