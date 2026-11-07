#requires -Version 7.2
param([Parameter(Mandatory)][string]$Directory,[Parameter(Mandatory)][string]$EncryptedReport,[Parameter(Mandatory)][string]$SnapshotDirectory,[string]$PepperPath='C:\Users\Admin\Documents\FamilyLocation-ServerCredentials\device-binding-pepper.v232.dpapi')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ParentRecovery.Common.ps1')
try{$checked=Test-RecoveryReport $Directory $EncryptedReport $SnapshotDirectory $PepperPath;$checked.Safe|ConvertTo-Json}
catch{
 $safe=@{'Report age invalid; inspect phone clock or generate a fresh report'='report_expired_or_clock_skew';'Report scope/signature metadata invalid'='report_scope_invalid';'Invalid report Auth metadata'='report_auth_metadata_invalid';'Family/Auth backup prerequisite failed'='snapshot_prerequisite_failed';'Inconsistent key presence metadata'='report_key_metadata_invalid';'Existing pepper invalid'='pepper_backup_invalid';'Unsupported report envelope'='unsupported_report_format'}
 $code=$safe[$_.Exception.Message];if(-not$code){$code='encrypted_report_validation_failed'}
 @{status='FAIL';code=$code;productionMutation=$false}|ConvertTo-Json -Compress;exit 1
}
