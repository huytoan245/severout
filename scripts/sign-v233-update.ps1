#requires -Version 7.2
param([switch]$CheckEnvironmentOnly)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'sign-release.ps1') -Version 2.3.3 -UpdateInPlace `
    -AndroidSdk 'C:\Users\Admin\AppData\Local\Android\Sdk' `
    -JavaHome 'C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1' `
    -UnsignedDirectory (Join-Path $PSScriptRoot '..\out\v233-recovery-update') `
    -CheckEnvironmentOnly:$CheckEnvironmentOnly
