#requires -Version 7.2
param(
    [string]$KeystorePath = 'C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks',
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$UnsignedDirectory = (Join-Path $PSScriptRoot '..\out\v230'),
    [string]$Version = '2.3.0'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
if ($Version -ne '2.3.0') { throw 'This gate requires v2.3.0/code34.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$docs = Join-Path $repo 'docs\RELEASE_SIGNING_V230.md'
$expected = Get-ReleaseSignerPin $docs
Assert-ReleaseKeystorePath $KeystorePath $repo
$java = Join-Path $JavaHome 'bin\java.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
foreach ($tool in @($java, $keytool, (Join-Path $buildTools 'lib\apksigner.jar'), (Join-Path $buildTools 'zipalign.exe'), (Join-Path $buildTools 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'Required signing/build tool missing.' }
}
$gate = Get-Content -LiteralPath (Join-Path $UnsignedDirectory 'AUTOMATED-GATE.json') -Raw | ConvertFrom-Json
if ($gate.status -ne 'PASS' -or -not $gate.inputHashes) { throw 'Automated build/test gate is missing or failed.' }
foreach ($property in $gate.inputHashes.PSObject.Properties) {
    $inputPath = Join-Path (Join-Path $repo 'appsrc') $property.Name
    if ((Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $property.Value) { throw 'Android input differs from the tested candidate; rebuild before signing.' }
}
$inputSums = Get-Content -LiteralPath (Join-Path $UnsignedDirectory 'SHA256SUMS.txt') -Raw
foreach ($app in @('Parent', 'Child')) {
    $name = "Family-$app-v$Version-unsigned.apk"
    $matches = [regex]::Matches($inputSums, ('(?m)^([a-f0-9]{64})  ' + [regex]::Escape($name) + '\r?$'))
    if ($matches.Count -ne 1 -or (Get-FileHash -LiteralPath (Join-Path $UnsignedDirectory $name) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $matches[0].Groups[1].Value) { throw 'Unsigned APK differs from the validated candidate.' }
}
$stage = Join-Path ([IO.Path]::GetTempPath()) ('family-location-sign-' + [guid]::NewGuid().ToString('N'))
$previous = $env:FAMILY_LOCATION_KS_PASSWORD
try {
    if (-not $env:FAMILY_LOCATION_KS_PASSWORD) { Read-ReleasePassword }
    if ((Get-ReleaseCertificateFingerprint $keytool $KeystorePath) -cne $expected) { throw 'Existing keystore does not match the pinned signer; nothing signed.' }
    New-Item -ItemType Directory -Path $stage | Out-Null
    $evidence = @()
    foreach ($app in @('Parent', 'Child')) {
        $inputApk = Join-Path $UnsignedDirectory "Family-$app-v$Version-unsigned.apk"
        $package = 'com.family.' + $app.ToLowerInvariant()
        $stagedInput = Join-Path $stage "$app-unsigned.apk"
        Copy-Item -LiteralPath $inputApk -Destination $stagedInput
        $identity = Invoke-ReleaseTool (Join-Path $buildTools 'aapt2.exe') @('dump', 'badging', $stagedInput)
        if ($identity.ExitCode -ne 0) { throw 'Unsigned APK badging failed.' }
        Assert-ReleaseApkIdentity $identity.Output $package
        $aligned = Join-Path $stage "$app-aligned.apk"
        $align = Invoke-ReleaseTool (Join-Path $buildTools 'zipalign.exe') @('-P', '16', '-f', '4', $stagedInput, $aligned)
        if ($align.ExitCode -ne 0) { throw 'zipalign failed.' }
        $signed = Join-Path $stage "Family-$app-v$Version-Installable.apk"
        $result = Invoke-ReleaseTool $java @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'sign', '--ks', $KeystorePath, '--ks-key-alias', $script:ReleaseAlias, '--ks-pass', 'env:FAMILY_LOCATION_KS_PASSWORD', '--key-pass', 'env:FAMILY_LOCATION_KS_PASSWORD', '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
        if ($result.ExitCode -ne 0) { throw 'Signing failed; neither Installable APK is promoted.' }
        $checked = Test-ReleaseApk $signed $package $java $buildTools $expected
        $evidence += $checked
        [IO.File]::WriteAllText((Join-Path $stage "$app-signature.txt"), $checked.Verification, [Text.UTF8Encoding]::new($false))
    }
    # Stage every delivery artifact only AFTER BOTH independent APK gates passed.
    $names = @('Family-Parent-v2.3.0-Installable.apk', 'Family-Child-v2.3.0-Installable.apk')
    $sums = for ($i = 0; $i -lt 2; $i++) { $evidence[$i].Sha256 + '  ' + $names[$i] }
    [IO.File]::WriteAllLines((Join-Path $stage 'SIGNED-SHA256SUMS.txt'), [string[]]$sums, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $docs -Destination (Join-Path $stage 'RELEASE_SIGNING_V230.md')
    $zipNames = $names + @('SIGNED-SHA256SUMS.txt', 'RELEASE_SIGNING_V230.md', 'Parent-signature.txt', 'Child-signature.txt')
    $releaseName = 'Family-Location-v2.3.0-Release.zip'
    Compress-Archive -LiteralPath @($zipNames | ForEach-Object { Join-Path $stage $_ }) -DestinationPath (Join-Path $stage $releaseName)
    $releaseHash = (Get-FileHash -LiteralPath (Join-Path $stage $releaseName) -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $stage 'RELEASE-SHA256.txt'), "$releaseHash  $releaseName`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $stage 'SIGNING-VALIDATION.json'), (@{ Status = 'PASS'; CertificateSha256 = $expected; APKs = $evidence; ReleaseSha256 = $releaseHash; PhysicalDevice = 'NOT VERIFIED'; Production = 'PAUSED' } | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'validate-signed-release.ps1') -AndroidSdk $AndroidSdk -JavaHome $JavaHome -Directory $stage
    foreach ($name in ($zipNames + @($releaseName, 'RELEASE-SHA256.txt', 'SIGNING-VALIDATION.json'))) {
        Copy-Item -LiteralPath (Join-Path $stage $name) -Destination (Join-Path $UnsignedDirectory $name)
    }
    Write-Output "Parent + Child signature/16KiB zipalign/package/version PASS. Certificate: $expected"
    Write-Output "Release ZIP SHA-256: $releaseHash"
    Write-Output "Outputs: $UnsignedDirectory. No app uninstall or production deployment performed."
} finally {
    $env:FAMILY_LOCATION_KS_PASSWORD = $previous
    # Public staging artifacts retained for diagnosis; no key/password is written here.
}
