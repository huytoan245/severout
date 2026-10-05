#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$Directory = (Join-Path $PSScriptRoot '..\out\v230')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
$expected = Get-ReleaseSignerPin (Join-Path $PSScriptRoot '..\docs\RELEASE_SIGNING_V230.md')
$java = Join-Path $JavaHome 'bin\java.exe'
$buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
$sums = Get-Content -LiteralPath (Join-Path $Directory 'SIGNED-SHA256SUMS.txt')
if ($sums.Count -ne 2) { throw 'Signed manifest must contain exactly the Parent and Child APKs.' }
$release = Join-Path $Directory 'Family-Location-v2.3.0-Release.zip'
$zip = [IO.Compression.ZipFile]::OpenRead($release)
try {
    $expectedNames = @('Family-Parent-v2.3.0-Installable.apk', 'Family-Child-v2.3.0-Installable.apk', 'SIGNED-SHA256SUMS.txt', 'RELEASE_SIGNING_V230.md', 'Parent-signature.txt', 'Child-signature.txt')
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
    $index = 0
    foreach ($app in @('Parent', 'Child')) {
        $name = "Family-$app-v2.3.0-Installable.apk"
        $checked = Test-ReleaseApk (Join-Path $Directory $name) ('com.family.' + $app.ToLowerInvariant()) $java $buildTools $expected
        if ($sums[$index] -cne ($checked.Sha256 + '  ' + $name)) { throw 'Signed APK manifest hash mismatch.' }
        $index++
        Write-Output "$app package/version/signature/zipalign PASS; APK SHA-256: $($checked.Sha256)"
    }
    $releaseHash = (Get-FileHash -LiteralPath $release -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-Content -LiteralPath (Join-Path $Directory 'RELEASE-SHA256.txt') -Raw).Trim() -cne "$releaseHash  Family-Location-v2.3.0-Release.zip") { throw 'Release ZIP manifest hash mismatch.' }
    Write-Output "Release ZIP SHA-256: $releaseHash"
} finally { $zip.Dispose() }
