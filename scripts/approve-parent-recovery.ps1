#requires -Version 7.2
param([Parameter(Mandatory)][string]$Directory,[Parameter(Mandatory)][string]$EncryptedReport,[Parameter(Mandatory)][string]$SnapshotDirectory,[switch]$ConfirmOriginalPhoneProfile,[string]$ExpectedRevision,[long]$ExpectedEpoch=0,[long]$ExpectedGeneration=-1,[string]$PepperPath='C:\Users\Admin\Documents\FamilyLocation-ServerCredentials\device-binding-pepper.v232.dpapi')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ParentRecovery.Common.ps1')
$checked=Test-RecoveryReport $Directory $EncryptedReport $SnapshotDirectory $PepperPath
if(-not$checked.Safe.eligibleForReview){throw 'STOP: binding/key/Auth conditions not eligible; no approval issued'}
$summary=Get-Content -LiteralPath (Join-Path $SnapshotDirectory 'READONLY-SUMMARY.json') -Raw|ConvertFrom-Json
if(-not$ConfirmOriginalPhoneProfile-or([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($summary.checkedAt)).TotalMinutes-gt5){throw 'Fresh read-only backup and explicit same-phone/profile confirmation required'}
if(-not$ExpectedRevision-or$checked.Safe.revision-cne$ExpectedRevision-or$checked.Safe.epoch-ne$ExpectedEpoch-or$checked.Safe.generation-ne$ExpectedGeneration){throw 'STOP: production differs from the explicitly reviewed revision/epoch/generation; never silently rebase approval'}
$checked.Safe|Select-Object bindingMatch,authPresent,authMatchesParent,authMatchesChild,keyPresent,keyUsable,rolesDistinct,revision,epoch,generation|ConvertTo-Json|Write-Host
Write-Host 'STOP until explicit approval: Parent-only new Anonymous UID/key, exact-snapshot rebind and epoch increment. No Child/history changes. Backend guard deployment requires separate approval.'
if((Read-Host 'Type APPROVE_PARENT_ONLY_REBIND only after reviewing this scope')-cne'APPROVE_PARENT_ONLY_REBIND'){throw 'No approval; nothing issued'}
$now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$operation=[guid]::NewGuid().ToString();$target=Join-Path $Directory 'Parent-only-approval.json'
if(Test-Path -LiteralPath $target){
 $previous=Get-Content -LiteralPath $target -Raw|ConvertFrom-Json;$previousRaw=[Convert]::FromBase64String($previous.payload)
 $reader=Read-RecoveryPrivateRsa $Directory
 try{if(-not$reader.VerifyData($previousRaw,[Convert]::FromBase64String($previous.signature),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)){throw 'Existing approval signature invalid; stop'}}finally{$reader.Dispose()}
 $previousScope=[Text.Encoding]::UTF8.GetString($previousRaw)|ConvertFrom-Json
 if($previousScope.reportHash-cne$checked.Safe.reportHash-or$previousScope.revision-cne$checked.Safe.revision-or$previousScope.epoch-ne$checked.Safe.epoch-or$previousScope.generation-ne$checked.Safe.generation){throw 'Existing operation no longer matches this report/snapshot; review without replacing identity'}
 $operation=$previousScope.operationId;$target=Join-Path $Directory "Parent-only-approval-$now.json"
}
$inspected=$checked.Raw|ConvertFrom-Json
$payload=[ordered]@{scope='PARENT_ONLY_REBIND';reportHash=$checked.Safe.reportHash;materialHash=(Get-RecoveryDigest $inspected.material);bindingMatched=$true;issuedAt=$now;expiresAt=$now+1800000;revision=$checked.Safe.revision;epoch=$checked.Safe.epoch;generation=$checked.Safe.generation;operationId=$operation;oldParentUidHash=(Get-RecoveryDigest $checked.Family.fields.parentUid.stringValue)}
$raw=[Text.Encoding]::UTF8.GetBytes(($payload|ConvertTo-Json -Compress))
$rsa=Read-RecoveryPrivateRsa $Directory
try{$signature=$rsa.SignData($raw,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)}finally{$rsa.Dispose()}
$envelope=@{payload=[Convert]::ToBase64String($raw);signature=[Convert]::ToBase64String($signature)}|ConvertTo-Json -Compress
Write-RecoveryNewFile $target ([Text.Encoding]::UTF8.GetBytes($envelope))
Write-Output 'Approval file issued locally. No server requests, Auth/key changes or rebind performed.'
