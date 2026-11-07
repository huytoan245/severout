#requires -Version 7.2
$ErrorActionPreference='Stop'
if(-not$IsWindows){Write-Output 'Windows DPAPI interoperability test requires local Windows; Android crypto/flow tests run separately.';return}
. (Join-Path $PSScriptRoot 'ParentRecovery.Common.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('family-parent-recovery-test-'+[guid]::NewGuid().ToString('N'))
$case=Join-Path $root 'case';$snapshot=Join-Path $root 'snapshot';New-Item -ItemType Directory -Path $snapshot -Force|Out-Null
$count=0
function Check([bool]$Ok,[string]$Name){if(-not$Ok){throw "FAIL: $Name"};$script:count++;Write-Output "PASS: $Name"}
function Reject([scriptblock]$Action,[string]$Name){$failed=$false;try{& $Action|Out-Null}catch{$failed=$true};Check $failed $Name}
try{
 & (Join-Path $PSScriptRoot 'new-parent-recovery-case.ps1') -Directory $case|Out-Null
 $privateHash=(Get-FileHash -LiteralPath (Join-Path $case 'operator-transport.dpapi')).Hash
 Reject {& (Join-Path $PSScriptRoot 'new-parent-recovery-case.ps1') -Directory $case} 'Existing operator case cannot be overwritten'
 $pepper=Get-RecoveryBase64Url ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 $pepperPath=Join-Path $root 'fixture-pepper.dpapi'
 [IO.File]::WriteAllBytes($pepperPath,[Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($pepper),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))
 $material=Get-RecoveryBase64Url ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 $hmac=[Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($pepper.Replace('-','+').Replace('_','/')+'='))
 try{$binding=Get-RecoveryBase64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("FL232-BIND`nfamily-01`nparent`n$material")))}finally{$hmac.Dispose()}
 @{fields=@{parentUid=@{stringValue='fixture-parent'};childUid=@{stringValue='fixture-child'};parentKey=@{stringValue='fixture-parent-key'};childKey=@{stringValue='fixture-child-key'};parentDeviceBindingHash=@{stringValue=$binding}};updateTime='2026-11-06T08:00:00.123456Z'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $snapshot 'family.private.json')
 @{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');schema=232;parentSlotPresent=$true;childSlotPresent=$true;parentBindingPresent=$true;parentAuthAccountExists=$true;parentAuthDisabled=$false;epoch=3;parentRebindGeneration=0}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $snapshot 'READONLY-SUMMARY.json')
 $report=@{format='FL233-PARENT-INSPECTION-1';role='parent';package='com.family.parent';signer='62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f';material=$material;collectedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();keyPresent=$false;keyUsable=$false;uid='fixture-parent'}
 $reportPath=Join-Path $root 'encrypted.json'
 function WriteEnvelope {
  $raw=[Text.Encoding]::UTF8.GetBytes(($report|ConvertTo-Json -Compress));$aesKey=[Security.Cryptography.RandomNumberGenerator]::GetBytes(32);$iv=[Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
  $cipher=[byte[]]::new($raw.Length);$tag=[byte[]]::new(16);$aes=[Security.Cryptography.AesGcm]::new($aesKey,16)
  try{$aes.Encrypt($iv,$raw,$cipher,$tag,[Text.Encoding]::UTF8.GetBytes('FL233-REPORT-1'))}finally{$aes.Dispose()}
  $rsa=Read-RecoveryPrivateRsa $case
  try{$wrapped=$rsa.Encrypt($aesKey,[Security.Cryptography.RSAEncryptionPadding]::OaepSHA256)}finally{$rsa.Dispose()}
  @{format='FL233-REPORT-1';iv=[Convert]::ToBase64String($iv);wrappedKey=[Convert]::ToBase64String($wrapped);ciphertext=[Convert]::ToBase64String(($cipher+$tag))}|ConvertTo-Json|Set-Content -LiteralPath $reportPath
 }
 WriteEnvelope
 $checked=Test-RecoveryReport $case $reportPath $snapshot $pepperPath
 Check ($checked.Safe.revision-is[string]-and$checked.Safe.revision-ceq'2026-11-06T08:00:00.123456Z') 'Firestore revision remains an exact string including microseconds'
 Check ($checked.Safe.eligibleForReview-and$checked.Safe.authPresent-and$checked.Safe.authMatchesParent) 'Retained Auth plus absent key is distinguished and binding matches'
 Check (-not(Get-Content -LiteralPath $reportPath -Raw).Contains('fixture-parent')) 'Transfer report has no plaintext identity'
 $report.uid=$null;WriteEnvelope;$checked=Test-RecoveryReport $case $reportPath $snapshot $pepperPath
 Check ($checked.Safe.eligibleForReview-and-not$checked.Safe.authPresent) 'Absent Auth is reported separately from absent key'
 foreach($uid in @('fixture-child','fixture-stranger')){$report.uid=$uid;WriteEnvelope;$checked=Test-RecoveryReport $case $reportPath $snapshot $pepperPath;Check (-not$checked.Safe.eligibleForReview) 'Wrong-role or unrelated retained Auth cannot be approved'}
 $report.uid='fixture-parent';$report.keyPresent=$true;$report.keyUsable=$true;WriteEnvelope
 Check (-not(Test-RecoveryReport $case $reportPath $snapshot $pepperPath).Safe.eligibleForReview) 'Existing usable key is preserved for normal UPDATE'
 $report.keyPresent=$false;$report.keyUsable=$false;$report.material=Get-RecoveryBase64Url ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));WriteEnvelope
 Check (-not(Test-RecoveryReport $case $reportPath $snapshot $pepperPath).Safe.eligibleForReview) 'Wrong device binding stops approval'
 $report.material=$material;$report.signer='wrong';WriteEnvelope;Reject {Test-RecoveryReport $case $reportPath $snapshot $pepperPath} 'Wrong signer metadata rejected'
 $report.signer='62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f';$report.collectedAt=1;WriteEnvelope;Reject {Test-RecoveryReport $case $reportPath $snapshot $pepperPath} 'Stale report rejected'
 $report.collectedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();WriteEnvelope
 $tampered=Get-Content -LiteralPath $reportPath -Raw|ConvertFrom-Json;$b=[Convert]::FromBase64String($tampered.ciphertext);$b[0]=$b[0]-bxor1;$tampered.ciphertext=[Convert]::ToBase64String($b);$tampered|ConvertTo-Json|Set-Content -LiteralPath $reportPath
 Reject {Test-RecoveryReport $case $reportPath $snapshot $pepperPath} 'AES-GCM tampering rejected'
 WriteEnvelope
 Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') -Directory $case -EncryptedReport $reportPath -SnapshotDirectory $snapshot -PepperPath $pepperPath} 'No same-phone confirmation means no approval and no prompt'
 Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') -Directory $case -EncryptedReport $reportPath -SnapshotDirectory $snapshot -PepperPath $pepperPath -ConfirmOriginalPhoneProfile -ExpectedRevision '2026-11-06T08:00:00.999999Z' -ExpectedEpoch 3 -ExpectedGeneration 0} 'Changed proposal snapshot cannot silently issue approval'
 $familyPath=Join-Path $snapshot 'family.private.json';$family=Get-Content -LiteralPath $familyPath -Raw|ConvertFrom-Json
 $family.fields.childKey.stringValue=$family.fields.parentKey.stringValue;$family|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $familyPath
 Check (-not(Test-RecoveryReport $case $reportPath $snapshot $pepperPath).Safe.eligibleForReview) 'Shared Parent/Child key stops to preserve Child access'
 Check (-not(Test-Path -LiteralPath (Join-Path $case 'Parent-only-approval.json'))) 'No approval produced by tests'
 # Explicit consent below applies only to this disposable fixture case.
 function Read-Host {param($Prompt) 'APPROVE_PARENT_ONLY_REBIND'}
 try{
  $family.fields.childKey.stringValue='fixture-child-key';$family.updateTime='2026-11-06T08:00:00.123456Z';$family|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $familyPath
  $approveArgs=@{Directory=$case;EncryptedReport=$reportPath;SnapshotDirectory=$snapshot;PepperPath=$pepperPath;ConfirmOriginalPhoneProfile=$true;ExpectedRevision='2026-11-06T08:00:00.123456Z';ExpectedEpoch=3;ExpectedGeneration=0}
  & (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs|Out-Null
  $grant=Get-Content -LiteralPath (Join-Path $case 'Parent-only-approval.json') -Raw|ConvertFrom-Json
  $grantBytes=[Convert]::FromBase64String($grant.payload);$grantText=[Text.Encoding]::UTF8.GetString($grantBytes)
  Check ((Get-RecoveryJsonString $grantText 'revision')-ceq$approveArgs.ExpectedRevision) 'Issued approval preserves the reviewed revision wire string'
  $reader=Read-RecoveryPrivateRsa $case
  try{Check ($reader.VerifyData($grantBytes,[Convert]::FromBase64String($grant.signature),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)) 'Exact-revision fixture approval signature verifies'}finally{$reader.Dispose()}
  $summaryPath=Join-Path $snapshot 'READONLY-SUMMARY.json';$summaryText=Get-Content -LiteralPath $summaryPath -Raw;$summary=$summaryText|ConvertFrom-Json
  $summary.checkedAt=[DateTimeOffset]::UtcNow.AddMinutes(-6).ToString('o');$summary|ConvertTo-Json|Set-Content -LiteralPath $summaryPath
  Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs} 'Stale snapshot rejects approval despite matching revision'
  $summary.checkedAt=[DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o');$summary|ConvertTo-Json|Set-Content -LiteralPath $summaryPath
  Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs} 'Future-dated snapshot rejects approval'
  Set-Content -LiteralPath $summaryPath -Value $summaryText
  & (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs|Out-Null
  $renewed=@(Get-ChildItem -LiteralPath $case -Filter 'Parent-only-approval-*.json')
  Check ($renewed.Count-eq1) 'Renewal creates one additional fixture approval without overwriting'
  $renewal=Get-Content -LiteralPath $renewed[0].FullName -Raw|ConvertFrom-Json
  $first=$grantText|ConvertFrom-Json;$second=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($renewal.payload))|ConvertFrom-Json
  Check ($first.operationId-ceq$second.operationId) 'Approval renewal retains the same operation despite timestamp JSON conversion'
  $originalReport=Join-Path $root 'original-encrypted.json';Copy-Item -LiteralPath $reportPath -Destination $originalReport
  $report.reportId=[guid]::NewGuid().ToString();WriteEnvelope
  Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs} 'Refreshed report cannot silently replace an approved report'
  Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs -PreviousEncryptedReport $reportPath} 'Wrong previous encrypted report cannot authorize refreshed inspection'
  & (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs -PreviousEncryptedReport $originalReport|Out-Null
  $replacement=Get-ChildItem -LiteralPath $case -Filter 'Parent-only-approval-*.json'|Sort-Object Name|Select-Object -Last 1
  $replacementEnvelope=Get-Content -LiteralPath $replacement.FullName -Raw|ConvertFrom-Json
  $replacementScope=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($replacementEnvelope.payload))|ConvertFrom-Json
  Check ($replacementScope.operationId-ceq$first.operationId-and$replacementScope.reportHash-ceq(Test-RecoveryReport $case $reportPath $snapshot $pepperPath).Safe.reportHash) 'Verified refreshed report retains original operation and binds the new exact report'
  $report.uid=$null;WriteEnvelope
  Reject {& (Join-Path $PSScriptRoot 'approve-parent-recovery.ps1') @approveArgs -PreviousEncryptedReport $originalReport} 'Changed local Auth cannot renew a refreshed-report approval'
 }finally{Remove-Item Function:\Read-Host}
 Check ((Get-FileHash -LiteralPath (Join-Path $case 'operator-transport.dpapi')).Hash-ceq$privateHash) 'Operator case private bytes unchanged'
 Write-Output "PASS: $count Parent recovery operator regressions. Ephemeral fixtures only; no live signer, pepper, Auth, device or production writes."
}finally{
 $resolved=[IO.Path]::GetFullPath($root);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
 if($resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase)-and[IO.Path]::GetFileName($resolved).StartsWith('family-parent-recovery-test-')){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
