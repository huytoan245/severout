# Existing v230 signer recovery — 05/10/2026

Later update05/10/2026: user completed actual local recovery/pinning; RELEASE_SIGNING_V230.md now contains the exact verified certificate. The earlier stop-state below documents the original recovery turn. Current tool-discovery/Unicode diagnosis and signing readiness are in SIGNING_ENVIRONMENT_V230.md. Actual APK signing still awaits local input; do not create another signer.

## Cause and evidence

Original post-creation command used unquoted `-J-Duser.language=en -J-Duser.country=US` directly in PowerShell. A password-free reproduction with the actual JDK17 keytool and `-help` returned exit1 and `Illegal option: .language=en`. Quoted arguments returned exit0. Key generation used no dotted JVM flags and had already succeeded. This failure does not establish any defect in the keystore/password/certificate; the user separately confirmed manual inspection PASS.

The original script also discarded the inspection diagnostic behind a generic message and depended on parsing localized certificate text. Native stderr may contain normal JKS format/export warnings; exit status must determine command success.

## Changes

- Shared native launcher uses ProcessStartInfo.ArgumentList, drains stdout/stderr independently and checks ExitCode. Password argument values contain only the environment variable name, never the secret.
- Read-only recovery verifies the existing file, exact alias/private-key entry, exports only the public DER certificate and hashes its bytes. Wrong password/alias/export/fingerprint or a different existing pin prevents pinning. Keystore file hash is checked before and after inspection. No generate/delete/import/changealias/storepass command is available.
- Allowed identity is exactly `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`, reported by user. Automated pin remains `NOT_CREATED` until real local inspection succeeds; we do not claim to have independently checked it yet.
- Old create-release-keystore.ps1 is a compatibility wrapper for recovery, not key creation. Signing rechecks the certificate and tested inputs, verifies exact package/version, one signer, apksigner and alignment for both apps, then checks ZIP/manifests before publication. Independent validator can rerun without passwords.
- Regression suite uses an isolated copy with public mock bytes for certificate/APK/failure flow, plus actual keytool argument transport. It creates no real keystore/signer. Simulated PASS is not real signing verification. CI runs this suite alongside existing Android/backend regressions.

## Stop gate and remaining work

Local regression result:37 PASS, including actual JDK17 option transport, native stderr success, exact/unchanged/idempotent pin policy, wrong APK identity/signer rejection, four Child signing/certificate/verify/alignment failures with no pair publication, mocked paired delivery, independent ZIP tamper rejection, alias/entry/password inspection failures and certificate-byte hashing. Static product preservation/canonical source checks also PASS. These results do not verify the actual existing signer or signed APKs.

The actual keystore exists outside Git. No signing password is available to the agent process. No real key inspection, pin, APK signing or signed Release ZIP has been completed in this recovery turn. Existing unsigned APKs retain their validated bytes:

|Artifact|SHA-256|
|---|---|
|Parent unsigned|`2a39439c809be2eb412200348919e5fae46450b941454458d4aa63d20ac1515e`|
|Child unsigned|`721bb9213a0cd8db1f68b14664783d698806274a8fcb1672fe7ace612a2984fd`|

Run the local recovery+sign command in RELEASE_SIGNING_V230.md and enter the password directly in PowerShell. If it fails, share only the error text; never send passwords or keys. Only public fingerprint/artifact hashes/validation results may be reported. After real pin/sign success, commit the verified public pin and report generated hashes; retain PR26 Draft and do not merge master, uninstall apps or deploy production.

Physical Android install/permissions/GPS/radio/Doze/boot/Samsung24–48h, new UIDs, production secrets/rules/Worker/FCM E2E remain NOT VERIFIED.
