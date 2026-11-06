#requires -Version 7.2
$ErrorActionPreference='Stop'
function Write-RecoveryNewFile([string]$Path,[byte[]]$Bytes){
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$stream.Write($Bytes)}finally{$stream.Dispose()}
}
function Get-RecoveryBase64Url([byte[]]$Bytes){[Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
function Get-RecoveryDigest([string]$Value){Get-RecoveryBase64Url ([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value)))}
function Read-RecoveryPrivateRsa([string]$Directory){
    if(-not $IsWindows){throw 'Local Windows DPAPI operator required'}
    $bytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $Directory 'operator-transport.dpapi')),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    $rsa=[Security.Cryptography.RSA]::Create()
    try{$read=0;$rsa.ImportPkcs8PrivateKey($bytes,[ref]$read);if($read-ne$bytes.Length){throw 'Invalid operator transport key'};return $rsa}finally{[Array]::Clear($bytes,0,$bytes.Length)}
}
function Read-RecoveryEnvelope([string]$Directory,[string]$EncryptedReport){
    if((Get-Item -LiteralPath $EncryptedReport).Length-gt 65536){throw 'Report too large'}
    $envelope=Get-Content -LiteralPath $EncryptedReport -Raw|ConvertFrom-Json
    if($envelope.format-cne'FL233-REPORT-1'){throw 'Unsupported report envelope'}
    $rsa=Read-RecoveryPrivateRsa $Directory
    try{$aesKey=$rsa.Decrypt([Convert]::FromBase64String($envelope.wrappedKey),[Security.Cryptography.RSAEncryptionPadding]::OaepSHA256)}finally{$rsa.Dispose()}
    $cipher=[Convert]::FromBase64String($envelope.ciphertext);$iv=[Convert]::FromBase64String($envelope.iv)
    if($cipher.Length-lt16-or$cipher.Length-gt16400-or$iv.Length-ne12-or$aesKey.Length-ne32){throw 'Invalid report envelope lengths'}
    $plain=[byte[]]::new($cipher.Length-16);$aes=[Security.Cryptography.AesGcm]::new($aesKey,16)
    try{$aes.Decrypt($iv,$cipher[0..($cipher.Length-17)],$cipher[($cipher.Length-16)..($cipher.Length-1)],$plain,[Text.Encoding]::UTF8.GetBytes('FL233-REPORT-1'));return [Text.Encoding]::UTF8.GetString($plain)}
    finally{$aes.Dispose();[Array]::Clear($aesKey,0,$aesKey.Length);[Array]::Clear($plain,0,$plain.Length)}
}
function Test-RecoveryReport([string]$Directory,[string]$EncryptedReport,[string]$SnapshotDirectory,[string]$PepperPath){
    $raw=Read-RecoveryEnvelope $Directory $EncryptedReport;$report=$raw|ConvertFrom-Json
    if($report.format-cne'FL233-PARENT-INSPECTION-1'-or$report.role-cne'parent'-or$report.package-cne'com.family.parent'-or$report.signer-cne'62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f'-or$report.material-cnotmatch'^[A-Za-z0-9_-]{43}$'-or$report.keyPresent-isnot[bool]-or$report.keyUsable-isnot[bool]){throw 'Report scope/signature metadata invalid'}
    $age=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-[long]$report.collectedAt
    if($age-lt-300000-or$age-gt86400000){throw 'Report age invalid; inspect phone clock or generate a fresh report'}
    $family=(Get-Content -LiteralPath (Join-Path $SnapshotDirectory 'family.private.json') -Raw|ConvertFrom-Json)
    $summary=Get-Content -LiteralPath (Join-Path $SnapshotDirectory 'READONLY-SUMMARY.json') -Raw|ConvertFrom-Json
    if(-not$summary.parentSlotPresent-or-not$summary.childSlotPresent-or-not$summary.parentBindingPresent-or$summary.schema-ne232-or$summary.parentAuthAccountExists-ne$true-or$summary.parentAuthDisabled-ne$false){throw 'Family/Auth backup prerequisite failed'}
    if($null-ne$report.uid -and ($report.uid-isnot[string]-or$report.uid.Length-lt1-or$report.uid.Length-gt128)){throw 'Invalid report Auth metadata'}
    if(-not$report.keyPresent-and$report.keyUsable){throw 'Inconsistent key presence metadata'}
    $uidMatchesParent=$report.uid -and $report.uid-ceq$family.fields.parentUid.stringValue
    $uidMatchesChild=$report.uid -and $report.uid-ceq$family.fields.childUid.stringValue
    # Read the EXISTING pepper only. Missing backup is a stop, never regeneration.
    $pepperBytes=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($PepperPath),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    try{
        $pepper=[Text.Encoding]::UTF8.GetString($pepperBytes)
        if($pepper-cnotmatch'^[A-Za-z0-9_-]{43}$'){throw 'Existing pepper invalid'}
        $key=[Convert]::FromBase64String($pepper.Replace('-','+').Replace('_','/')+'=')
        $hmac=[Security.Cryptography.HMACSHA256]::new($key)
        try{$computed=$hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("FL232-BIND`nfamily-01`nparent`n$($report.material)"))}finally{$hmac.Dispose();[Array]::Clear($key,0,$key.Length)}
        $expected=[Convert]::FromBase64String($family.fields.parentDeviceBindingHash.stringValue.Replace('-','+').Replace('_','/')+'=')
        $match=[Security.Cryptography.CryptographicOperations]::FixedTimeEquals($computed,$expected)
    }finally{[Array]::Clear($pepperBytes,0,$pepperBytes.Length);$pepper=$null}
    $safe=[ordered]@{bindingMatch=$match;authPresent=[bool]$report.uid;authMatchesParent=[bool]$uidMatchesParent;authMatchesChild=[bool]$uidMatchesChild;keyPresent=$report.keyPresent;keyUsable=$report.keyUsable;eligibleForReview=($match-and-not$report.keyPresent-and-not$uidMatchesChild-and(-not$report.uid-or$uidMatchesParent));revision=$family.updateTime;epoch=$summary.epoch;generation=$summary.parentRebindGeneration;reportHash=(Get-RecoveryDigest $raw);productionMutation=$false}
    return @{Safe=$safe;Raw=$raw;Family=$family}
}
