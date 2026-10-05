#requires -Version 7.2
param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
$script:passed = 0
function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
    Write-Output "PASS: $Name"
}
function Reject([scriptblock]$Action, [string]$Name) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Check $rejected $Name
}
# No production keystore, private key, password or Android app is used by this suite.
$root = Join-Path ([IO.Path]::GetTempPath()) ('family-signing-regression-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$previousPassword = $env:FAMILY_LOCATION_KS_PASSWORD
$previousMode = $env:FAMILY_SIGNING_TEST_MODE
try {
    foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
        $tokens = $null; $errors = $null
        [void][Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
        Check ($errors.Count -eq 0) "Parser $($file.Name)"
    }
    $keytool = Join-Path $JavaHome $(if ($IsWindows) { 'bin/keytool.exe' } else { 'bin/keytool' })
    $native = Invoke-ReleaseTool $keytool @('-J-Duser.language=en', '-J-Duser.country=US', '-help')
    Check ($native.ExitCode -eq 0) 'Real keytool receives intact dotted JVM options; stderr help is not a failure'
    $receiver = Join-Path $root 'argument receiver.ps1'
    [IO.File]::WriteAllText($receiver, '$args | ConvertTo-Json -Compress', [Text.UTF8Encoding]::new($false))
    $received = Invoke-ReleaseTool (Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })) @('-NoProfile', '-File', $receiver, '-J-Duser.language=en', 'path with spaces')
    $arguments = $received.Output | ConvertFrom-Json
    Check ($received.ExitCode -eq 0 -and $arguments[0] -ceq '-J-Duser.language=en' -and $arguments[1] -ceq 'path with spaces') 'Native argv preserves dotted flag and spaced path'
    $stderr = Invoke-ReleaseTool (Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })) @('-NoProfile', '-Command', '[Console]::Error.WriteLine("public warning"); exit 0')
    Check ($stderr.ExitCode -eq 0 -and $stderr.ErrorOutput.Contains('public warning')) 'Zero exit with native stderr warning accepted'

    $document = Join-Path $root 'pin.md'
    [IO.File]::WriteAllText($document, 'Pinned signer SHA-256: `NOT_CREATED`' + "`nStatus: pending.")
    Reject { Get-ReleaseSignerPin $document } 'Missing pin rejected'
    Reject { Set-ReleaseSignerPin $document ('0' * 64) } 'Wrong fingerprint cannot pin'
    Check ((Get-Content -LiteralPath $document -Raw).Contains('NOT_CREATED')) 'Wrong fingerprint leaves document unchanged'
    Set-ReleaseSignerPin $document $script:ReleaseFingerprint
    Check ((Get-ReleaseSignerPin $document) -ceq $script:ReleaseFingerprint) 'Exact public fingerprint pins successfully'
    Set-ReleaseSignerPin $document $script:ReleaseFingerprint
    Check ((Get-ReleaseSignerPin $document) -ceq $script:ReleaseFingerprint) 'Same pin recovery is idempotent'
    [IO.File]::WriteAllText($document, 'Pinned signer SHA-256: `' + ('f' * 64) + '`')
    Reject { Set-ReleaseSignerPin $document $script:ReleaseFingerprint } 'Existing different pin cannot be replaced'
    Assert-ReleaseApkIdentity "package: name='com.family.parent' versionCode='34' versionName='2.3.0'" 'com.family.parent'
    Check $true 'Exact APK identity accepted'
    Reject { Assert-ReleaseApkIdentity "package: name='comXfamilyXparent' versionCode='34' versionName='2.3.0'" 'com.family.parent' } 'Package punctuation is literal'
    Reject { Assert-ReleaseApkIdentity "package: name='com.family.parent' versionCode='33' versionName='2.3.0'" 'com.family.parent' } 'Wrong versionCode rejected'
    Reject { Assert-ReleaseApkIdentity "package: name='com.family.parent' versionCode='34' versionName='2.3X0'" 'com.family.parent' } 'Wrong versionName rejected'
    Assert-ReleaseApkCertificate ('Signer #1 certificate SHA-256 digest: ' + $script:ReleaseFingerprint) $script:ReleaseFingerprint
    Check $true 'Exact sole signer accepted'
    Reject { Assert-ReleaseApkCertificate ('Signer #1 certificate SHA-256 digest: ' + ('0' * 64)) $script:ReleaseFingerprint } 'Wrong APK signer rejected'
    Reject { Assert-ReleaseApkCertificate (('Signer #1 certificate SHA-256 digest: ' + $script:ReleaseFingerprint) + "`nSigner #2 certificate SHA-256 digest: " + $script:ReleaseFingerprint) $script:ReleaseFingerprint } 'Multiple APK signers rejected'

    # Instrument only an isolated COPY. Public mock bytes are not a real certificate,
    # keystore or installable APK; this tests failure/control flow, not real signing.
    $copy = Join-Path $root 'repo'
    foreach ($dir in @('scripts', 'docs', 'appsrc', 'out')) { New-Item -ItemType Directory -Path (Join-Path $copy $dir) | Out-Null }
    foreach ($name in @('ReleaseSigning.Common.ps1', 'sign-release.ps1', 'validate-signed-release.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $copy 'scripts') }
    $fixtureStage = Join-Path $root 'staging'
    New-Item -ItemType Directory -Path $fixtureStage | Out-Null
    $copiedGate = Join-Path $copy 'scripts/sign-release.ps1'
    $gateText = (Get-Content -LiteralPath $copiedGate -Raw).Replace('([IO.Path]::GetTempPath())', ("('" + $fixtureStage.Replace("'", "''") + "')"))
    [IO.File]::WriteAllText($copiedGate, $gateText, [Text.UTF8Encoding]::new($false))
    $mock = @'
function Invoke-ReleaseTool {
    param([string]$Executable, [string[]]$Arguments)
    $exitCode = 0; $output = ''
    $apk = $Arguments[-1]
    if ($Executable.EndsWith('aapt2.exe')) {
        $app = if ($apk.Contains('Parent')) { 'parent' } else { 'child' }
        $output = "package: name='com.family.$app' versionCode='34' versionName='2.3.0'"
    } elseif ($Executable.EndsWith('zipalign.exe')) {
        if ($Arguments[0] -eq '-c') {
            if ($env:FAMILY_SIGNING_TEST_MODE -eq 'child-align' -and $apk.Contains('Child')) { $exitCode = 1 }
        } else { Copy-Item -LiteralPath $Arguments[-2] -Destination $apk }
    } elseif ($Arguments -contains 'sign') {
        if ($env:FAMILY_SIGNING_TEST_MODE -eq 'child-sign' -and $apk.Contains('Child')) { $exitCode = 1 }
        else { $outIndex = [array]::IndexOf($Arguments, '--out') + 1; Copy-Item -LiteralPath $apk -Destination $Arguments[$outIndex] }
    } elseif ($Arguments -contains 'verify') {
        $digest = $script:ReleaseFingerprint
        if ($env:FAMILY_SIGNING_TEST_MODE -eq 'child-cert' -and $apk.Contains('Child')) { $digest = '0' * 64 }
        if ($env:FAMILY_SIGNING_TEST_MODE -eq 'child-verify' -and $apk.Contains('Child')) { $exitCode = 1 }
        $output = 'Signer #1 certificate SHA-256 digest: ' + $digest
    }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output; ErrorOutput = '' }
}
function Get-ReleaseCertificateFingerprint { $script:ReleaseFingerprint }
'@
    [IO.File]::AppendAllText((Join-Path $copy 'scripts/ReleaseSigning.Common.ps1'), "`n" + $mock, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $copy 'docs/RELEASE_SIGNING_V230.md'), 'Pinned signer SHA-256: `' + $script:ReleaseFingerprint + '`')
    $fakeStore = Join-Path $root 'read-only-fixture.bin'
    [IO.File]::WriteAllText($fakeStore, 'public non-keystore fixture')
    $storeHash = (Get-FileHash -LiteralPath $fakeStore).Hash
    $javaDir = Join-Path $root 'java'
    $sdkDir = Join-Path $root 'sdk'
    foreach ($path in @('java/bin/java.exe', 'java/bin/keytool.exe', 'sdk/build-tools/36.0.0/lib/apksigner.jar', 'sdk/build-tools/36.0.0/zipalign.exe', 'sdk/build-tools/36.0.0/aapt2.exe')) {
        $full = Join-Path $root $path
        New-Item -ItemType Directory -Path (Split-Path $full) -Force | Out-Null
        [IO.File]::WriteAllText($full, 'non-executable fixture')
    }
    $out = Join-Path $copy 'out'
    $input = Join-Path $copy 'appsrc/input.txt'
    [IO.File]::WriteAllText($input, 'public Android input fixture')
    @{ status = 'PASS'; inputHashes = @{ 'input.txt' = (Get-FileHash -LiteralPath $input).Hash.ToLowerInvariant() } } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $out 'AUTOMATED-GATE.json')
    $sumLines = foreach ($app in @('Parent', 'Child')) {
        $name = "Family-$app-v2.3.0-unsigned.apk"
        [IO.File]::WriteAllText((Join-Path $out $name), "public mock $app APK - never install")
        (Get-FileHash -LiteralPath (Join-Path $out $name)).Hash.ToLowerInvariant() + '  ' + $name
    }
    $sumLines | Set-Content -LiteralPath (Join-Path $out 'SHA256SUMS.txt')
    $env:FAMILY_LOCATION_KS_PASSWORD = 'NON_SECRET_MOCK_INPUT_NOT_A_KEYSTORE_PASSWORD'
    foreach ($mode in @('child-sign', 'child-cert', 'child-verify', 'child-align')) {
        $env:FAMILY_SIGNING_TEST_MODE = $mode
        Reject { & (Join-Path $copy 'scripts/sign-release.ps1') -KeystorePath $fakeStore -JavaHome $javaDir -AndroidSdk $sdkDir -UnsignedDirectory $out } "Pair gate rejects $mode"
        Check (@(Get-ChildItem -LiteralPath $out -Filter '*Installable.apk').Count -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $out 'Family-Location-v2.3.0-Release.zip'))) "No Installable/Release output after $mode"
    }
    $env:FAMILY_SIGNING_TEST_MODE = 'pass'
    & (Join-Path $copy 'scripts/sign-release.ps1') -KeystorePath $fakeStore -JavaHome $javaDir -AndroidSdk $sdkDir -UnsignedDirectory $out | Out-Null
    Check (@(Get-ChildItem -LiteralPath $out -Filter '*Installable.apk').Count -eq 2 -and (Test-Path -LiteralPath (Join-Path $out 'Family-Location-v2.3.0-Release.zip'))) 'Both verified mock outputs promoted together with ZIP and manifest'
    Check ((Get-FileHash -LiteralPath $fakeStore).Hash -ceq $storeHash) 'Read-only fixture bytes preserved'
    [IO.File]::AppendAllText((Join-Path $out 'Family-Child-v2.3.0-Installable.apk'), 'tamper')
    Reject { & (Join-Path $copy 'scripts/validate-signed-release.ps1') -JavaHome $javaDir -AndroidSdk $sdkDir -Directory $out } 'Independent validator rejects altered APK/ZIP mismatch'
    # Reload real helpers, then test inspection with an isolated mocked native reader.
    . (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
    function Invoke-ReleaseTool {
        param([string]$Executable, [string[]]$Arguments)
        if ($Arguments -contains '-list') {
            $alias = if ($env:FAMILY_SIGNING_TEST_MODE -eq 'wrong-alias') { 'different-alias' } else { $script:ReleaseAlias }
            $type = if ($env:FAMILY_SIGNING_TEST_MODE -eq 'public-only') { 'trustedCertEntry' } else { 'PrivateKeyEntry' }
            [pscustomobject]@{ ExitCode = $(if ($env:FAMILY_SIGNING_TEST_MODE -eq 'bad-password') { 1 } else { 0 }); Output = "Alias name: $alias`nEntry type: $type`n"; ErrorOutput = 'public JKS warning' }
        } else {
            $destination = $Arguments[[array]::IndexOf($Arguments, '-file') + 1]
            [IO.File]::WriteAllText($destination, 'mock certificate DER bytes')
            [pscustomobject]@{ ExitCode = 0; Output = ''; ErrorOutput = 'public export warning' }
        }
    }
    foreach ($mode in @('wrong-alias', 'public-only', 'bad-password')) {
        $env:FAMILY_SIGNING_TEST_MODE = $mode
        Reject { Get-ReleaseCertificateFingerprint $keytool $fakeStore } "Read-only inspection rejects $mode"
    }
    $env:FAMILY_SIGNING_TEST_MODE = 'pass'
    $digest = Get-ReleaseCertificateFingerprint $keytool $fakeStore
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $expectedDigest = [Convert]::ToHexString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes('mock certificate DER bytes'))).ToLowerInvariant() }
    finally { $hash.Dispose() }
    Check ($digest -ceq $expectedDigest -and (Get-FileHash -LiteralPath $fakeStore).Hash -ceq $storeHash) 'DER hash calculated without mutating keystore bytes'
    Write-Output "PASS: $script:passed signing regressions. Mock gates are NOT actual APK signature/device verification."
} finally {
    $env:FAMILY_LOCATION_KS_PASSWORD = $previousPassword
    $env:FAMILY_SIGNING_TEST_MODE = $previousMode
    # Resolve and verify the single generated fixture root before recursive deletion.
    $resolved = [IO.Path]::GetFullPath($root)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved).StartsWith('family-signing-regression-')) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
