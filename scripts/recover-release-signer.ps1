#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$KeystorePath = 'C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks',
    [switch]$SignAfterPin,
    [string]$AndroidSdk,
    [string]$UnsignedDirectory = (Join-Path $PSScriptRoot '..\out\v230'),
    [string]$BuildToolsVersion
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$docs = Join-Path $repo 'docs\RELEASE_SIGNING_V230.md'
Assert-ReleaseKeystorePath $KeystorePath $repo
$text = Get-Content -LiteralPath $docs -Raw
$pins = [regex]::Matches($text, 'Pinned signer SHA-256: `([^`]+)`')
if ($pins.Count -ne 1 -or $pins[0].Groups[1].Value -cnotin @('NOT_CREATED', $script:ReleaseFingerprint)) { throw 'Existing pin differs; recovery cannot replace a signer.' }
if ($SignAfterPin -and -not $AndroidSdk) { throw 'SignAfterPin requires AndroidSdk.' }
if ($SignAfterPin) { Resolve-ReleaseSigningEnvironment $AndroidSdk $JavaHome $BuildToolsVersion (Join-Path $repo 'appsrc') (Join-Path $UnsignedDirectory 'ANDROID-BUILD-TOOLS.json') | Out-Null }
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JDK keytool missing.' }
$previous = $env:FAMILY_LOCATION_KS_PASSWORD
try {
    if (-not $env:FAMILY_LOCATION_KS_PASSWORD) { Read-ReleasePassword }
    $fingerprint = Get-ReleaseCertificateFingerprint $keytool $KeystorePath
    Set-ReleaseSignerPin $docs $fingerprint
    Write-Output "Existing public certificate verified/pinned: $fingerprint"
    if ($SignAfterPin) {
        & (Join-Path $PSScriptRoot 'sign-release.ps1') -KeystorePath $KeystorePath -AndroidSdk $AndroidSdk -JavaHome $JavaHome -UnsignedDirectory $UnsignedDirectory -BuildToolsVersion $BuildToolsVersion
    }
} finally { $env:FAMILY_LOCATION_KS_PASSWORD = $previous }
