#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$SigningDirectory = 'C:\Users\Admin\Documents\FamilyLocation-Signing'
)
$ErrorActionPreference = 'Stop'
# Existing v230 signer only: creation, overwrite and alias changes are retired.
Write-Output 'Keystore creation is retired for v230. Inspecting/pinning the EXISTING signer only.'
& (Join-Path $PSScriptRoot 'recover-release-signer.ps1') -JavaHome $JavaHome -KeystorePath (Join-Path $SigningDirectory 'Family-Location-Release-2026.jks')
