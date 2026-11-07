#requires -Version 7.2
param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Bootstrap.Common.ps1')
. (Join-Path $PSScriptRoot 'ParentRecovery.Common.ps1')
$safe=Assert-BootstrapExternalPath $Directory ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))
# Reuses only the ACL directory helper; creates no bootstrap or release signer.
New-BootstrapPrivateDirectory $safe
$rsa=[Security.Cryptography.RSA]::Create(3072)
try{
    $private=$rsa.ExportPkcs8PrivateKey()
    $protected=[Security.Cryptography.ProtectedData]::Protect($private,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    Write-RecoveryNewFile (Join-Path $safe 'operator-transport.dpapi') $protected
    Write-RecoveryNewFile (Join-Path $safe 'operator-public.txt') ([Text.Encoding]::UTF8.GetBytes([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())))
}finally{$rsa.Dispose();if($private){[Array]::Clear($private,0,$private.Length)}}
Write-Output 'PASS: operator encryption/approval case created outside Git. No release signer, bootstrap, Auth user or installation key created.'
