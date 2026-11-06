#requires -Version 7.2
param([Parameter(Mandatory)][string]$Directory,[Parameter(Mandatory)][string]$NodePath,[Parameter(Mandatory)][string]$CredentialFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Bootstrap.Common.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$safe=Assert-BootstrapExternalPath $Directory $repo
New-BootstrapPrivateDirectory $safe
& $NodePath (Join-Path $repo 'cloudflare-wake/backup-parent-recovery.mjs') --credential-file $CredentialFile --output $safe
if($LASTEXITCODE-ne0){throw 'Read-only backup failed; preserve evidence and stop'}
