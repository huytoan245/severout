# Signing environment diagnosis and gate — 05/10/2026

## Nguyên nhân

The reported `Required signing/build tool missing` did not record which path failed. Direct current inventory shows no missing required tool at the supplied paths, and the unmodified prior script with those exact arguments reached its SecureString prompt in a controlled noninteractive run. The original failed invocation's particular missing path cannot be established from that generic message. There is no evidence that36.0.0 is the wrong version or that an SDK package needs installation.

A subsequent real preflight exposed a separate reproducible failure: Windows zipalign exits1 with `Unable to open` at the Vietnamese project path. It exits0 on the ASCII build path for the same Parent APK SHA-256. This is a native filename limitation, not a damaged APK or missing tool. The former blanket error and hard-coded discovery made diagnosis unreliable; both are repaired.

## Đã sửa

ReleaseSigning.Common.ps1 resolves explicit version → evaluated/matching Android metadata or explicit Android build file → highest complete installed stable version. It validates all required paths, skips preview/broken packages and names each missing tool/path. SDK batch wrappers are supported by locating their actual JAR and invoking it through the provided Java. Native APK inputs use SHA-256-checked ASCII staging when needed. All archive entries are read/decompressed, and signed integrity additionally requires apksigner verify.

sign-release.ps1 provides -BuildToolsVersion and -CheckEnvironmentOnly. It validates tools, the unchanged automated candidate inputs, real APK identity/readability/alignment before password input. recover-release-signer.ps1 checks tool discovery before its password prompt for -SignAfterPin. validate-signed-release.ps1 uses the same discovery/staging, verifies the two APKs and every ZIP member/hash plus release metadata. capture-build-tools.gradle records actual AGP values and project hashes during build. No Android logic/dependency/SDK package changed.

## Signing environment

|Item|Actual path/value|
|---|---|
|Android SDK|`C:\Users\Admin\AppData\Local\Android\Sdk`|
|Build-tools selected|`36.0.0`, evaluated by actual Gradle for Parent and Child|
|aapt2|`C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe`|
|zipalign|`C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\36.0.0\zipalign.exe`|
|apksigner|`C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\36.0.0\lib\apksigner.jar`|
|Wrapper also exists|`C:\Users\Admin\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat`|
|Java|`C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1\bin\java.exe`|
|keytool|`C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1\bin\keytool.exe`|
|SDK inventory|build-tools36.0.0; platform-tools present; cmdline-tools/latest present; platforms/android-36 present|

No package downloaded. The first local Gradle query hit the same Unicode .bat invocation limitation; rerunning the identical init script from the existing ASCII build mirror completed successfully and captured evaluated36.0.0. Source-byte equality was checked against the original automated gate.

## Signer

Actual local recovery/pin PASS was reported by the user and recorded in RELEASE_SIGNING_V230.md before this turn. Sole fingerprint: `62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f`. Alias `family-location-release-2026`. Keystore outside repo unchanged: file SHA-256 `71a8de8f085e05d0a229fb2da0cd6ec95e75b6a7592a4e986b8ea1aafefc4ddb` before/after (this is the JKS file checksum, not the certificate fingerprint). No regenerate/overwrite/alias migration/private-key extraction or key/password commit.

## Parent / Child / Release

Actual unsigned Parent `com.family.parent` and Child `com.family.child`, version2.3.0/code34: hash/input checks, badging, every ZIP entry readable and16KiB alignment PASS via real installed tools. No password is available in the Codex process; real signing/certificate/apksigner checks on signed APKs remain NOT VERIFIED. No Installable APK, SIGNED-SHA256SUMS.txt or signed Release ZIP exists yet. Do not substitute unsigned hashes for signed release hashes.

## Regression

55 signing checks PASS: real keytool argument transport; exact missing aapt2/zipalign/apksigner/Java/keytool diagnostics; explicit/project/highest version priority; preview/broken/stale metadata rejection; SDK JAR layout; archive readability; Unicode staging/checksum/cleanup; unchanged/exact pin; wrong package/version/signer and four single-Child failure modes with no release publication; paired mocked delivery/metadata/ZIP and tamper rejection. No real private key used by tests. Mock PASS is not actual signing PASS.

Static product preservation and canonical source PASS. Actual Gradle coreSelfCheck/scenarioCheck PASS. Environment-only gate PASS on actual Parent+Child unsigned inputs; all61 Android input hashes unchanged. CI exact commit/status is linked on PR26; must be successful before final readiness report. PR remains Draft, no master merge.

## Chưa kiểm tra / stop

Actual paired signing/Installable/release ZIP awaits direct local PowerShell password input. Use the direct signing command in RELEASE_SIGNING_V230.md; never send secrets into chat. Phone installation, new UIDs, FCM/Cloudflare production, GPS/Doze/reboot and Samsung24–48h remain NOT VERIFIED. User reports old apps removed; Codex performs no uninstall/installation, data deletion, production configuration/deploy or master merge. After signed gates PASS, stop before the user install → permissions → exact UIDs → secrets/rules → production → E2E → endurance sequence.
