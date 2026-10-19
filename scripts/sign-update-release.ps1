#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$UnsignedDirectory=(Join-Path $PSScriptRoot '..\out\v232-reliable-update'),
    [switch]$CheckEnvironmentOnly
)
$ErrorActionPreference='Stop'
# Same release signer and all paired gates; the mode only changes enrollment input.
& (Join-Path $PSScriptRoot 'sign-release.ps1') -Version 2.3.2 -UpdateInPlace -AndroidSdk $AndroidSdk -JavaHome $JavaHome -UnsignedDirectory $UnsignedDirectory -BuildToolsVersion 36.0.0 -CheckEnvironmentOnly:$CheckEnvironmentOnly
