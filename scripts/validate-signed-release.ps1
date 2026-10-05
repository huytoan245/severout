#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$Directory = (Join-Path $PSScriptRoot '..\out\v230'),
    [string]$BuildToolsVersion
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
$expected = Get-ReleaseSignerPin (Join-Path $PSScriptRoot '..\docs\RELEASE_SIGNING_V230.md')
$java = Join-Path $JavaHome 'bin\java.exe'
$tools = Resolve-ReleaseSigningEnvironment $AndroidSdk $JavaHome $BuildToolsVersion (Join-Path $PSScriptRoot '../appsrc') (Join-Path $Directory 'ANDROID-BUILD-TOOLS.json')
$buildTools = $tools.Directory
$sums = Get-Content -LiteralPath (Join-Path $Directory 'SIGNED-SHA256SUMS.txt')
if ($sums.Count -ne 2) { throw 'Signed manifest must contain exactly the Parent and Child APKs.' }
$release = Join-Path $Directory 'Family-Location-v2.3.0-Release.zip'
$zip = [IO.Compression.ZipFile]::OpenRead($release)
try {
    $expectedNames = @('Family-Parent-v2.3.0-Installable.apk', 'Family-Child-v2.3.0-Installable.apk', 'SIGNED-SHA256SUMS.txt', 'RELEASE_SIGNING_V230.md', 'Parent-signature.txt', 'Child-signature.txt', 'RELEASE-INFO.json')
    if ($zip.Entries.Count -ne $expectedNames.Count) { throw 'Unexpected release ZIP entries.' }
    foreach ($name in $expectedNames) {
        $entries = @($zip.Entries | Where-Object { $_.FullName -ceq $name })
        if ($entries.Count -ne 1) { throw 'Missing/duplicate release ZIP entry.' }
        $stream = $entries[0].Open()
        $hash = [Security.Cryptography.SHA256]::Create()
        try { $zipHash = [Convert]::ToHexString($hash.ComputeHash($stream)).ToLowerInvariant() }
        finally { $hash.Dispose(); $stream.Dispose() }
        if ($zipHash -cne (Get-FileHash -LiteralPath (Join-Path $Directory $name) -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Release ZIP does not match the verified outputs.' }
    }
    $info = Get-Content -LiteralPath (Join-Path $Directory 'RELEASE-INFO.json') -Raw | ConvertFrom-Json
    if ($info.VersionName -cne '2.3.0' -or $info.VersionCode -ne 34 -or $info.CertificateSha256 -cne $expected -or $info.APKs.Count -ne 2) { throw 'Release metadata version/certificate/pair mismatch.' }
    $index = 0
    foreach ($app in @('Parent', 'Child')) {
        $name = "Family-$app-v2.3.0-Installable.apk"
        $checked = Test-ReleaseApk (Join-Path $Directory $name) ('com.family.' + $app.ToLowerInvariant()) $java $buildTools $expected $tools.ApkSignerJar
        $record = $info.APKs[$index]
        if ($record.Package -cne $checked.Package -or $record.VersionName -cne $checked.VersionName -or $record.VersionCode -ne $checked.VersionCode -or $record.CertificateSha256 -cne $expected -or $record.Sha256 -cne $checked.Sha256) { throw 'Release metadata differs from verified APK.' }
        if ($sums[$index] -cne ($checked.Sha256 + '  ' + $name)) { throw 'Signed APK manifest hash mismatch.' }
        $index++
        Write-Output "$app package/version/signature/zipalign PASS; APK SHA-256: $($checked.Sha256)"
    }
    $releaseHash = (Get-FileHash -LiteralPath $release -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-Content -LiteralPath (Join-Path $Directory 'RELEASE-SHA256.txt') -Raw).Trim() -cne "$releaseHash  Family-Location-v2.3.0-Release.zip") { throw 'Release ZIP manifest hash mismatch.' }
    Write-Output "Release ZIP SHA-256: $releaseHash"
} finally { $zip.Dispose() }
