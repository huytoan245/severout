#requires -Version 7.2
. (Join-Path $PSScriptRoot 'Bootstrap.V232.Common.ps1')
$count=0
function Check([bool]$Ok,[string]$Label) { if (-not $Ok) { throw $Label }; $script:count++ }
function Reject([scriptblock]$Action,[string]$Label) { $failed=$false; try { & $Action } catch { $failed=$true }; Check $failed $Label }
$root=Join-Path ([IO.Path]::GetTempPath()) ('family-bootstrap-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$tokens=@{}
foreach ($role in @('parent','child')) { $tokens[$role]=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_') }
$expiry=[DateTimeOffset]::UtcNow.AddDays(7).ToUnixTimeMilliseconds()
$manifest=@{familyId='family-01';deviceId='child-01';versionName='2.3.2';versionCode=36;bootstrapMode='UNTIL_CONSUMED_OR_REVOKED';roles=@{}}
foreach ($role in @('parent','child')) { $manifest.roles[$role]=@{sha256=(Get-BootstrapHash $tokens[$role])} }
function WriteManifest { $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $root 'BOOTSTRAP-PROVISIONING.json') }
function WriteApks([string]$Mode) {
    foreach ($role in @('parent','child')) {
        $app=(Get-Culture).TextInfo.ToTitleCase($role);$path=Join-Path $root "Family-$app-v2.3.2-unsigned.apk"
        if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
        $zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
        try {
            $stream=$zip.CreateEntry('classes.dex').Open()
            try {
                $other=if($role -ceq 'parent'){'child'}else{'parent'}
                $value=if($Mode -ceq 'missing'){''}elseif($Mode -ceq 'swapped'){$tokens[$other]}elseif($Mode -ceq 'both'){$tokens[$role]+[char]0+[char]43+$tokens[$other]}else{$tokens[$role]}
                $bytes=[Text.Encoding]::ASCII.GetBytes([char]43+$value+[char]0);$stream.Write($bytes)
            } finally { $stream.Dispose() }
        } finally { $zip.Dispose() }
    }
}
try {
    Reject { Assert-BootstrapApkPairV232 $root } 'Missing provisioning blocks signing'
    WriteManifest;WriteApks 'correct';Assert-BootstrapApkPairV232 $root;Check $true 'Correct separate role capabilities found by hash'
    foreach($mode in @('missing','swapped','both')) { WriteApks $mode;Reject { Assert-BootstrapApkPairV232 $root } "Reject $mode capability APKs" }
    WriteApks 'correct';$manifest.roles.parent.expiresAt=1;WriteManifest;Reject { Assert-BootstrapApkPairV232 $root } 'Initial expiry is forbidden'
    $manifest.roles.parent.Remove('expiresAt');$manifest.roles.parent.token=$tokens.parent;WriteManifest;Reject { Assert-BootstrapApkPairV232 $root } 'Plaintext in report rejected'
    $manifest.roles.parent.Remove('token');WriteManifest
    Check (-not (Get-Content -LiteralPath (Join-Path $root 'BOOTSTRAP-PROVISIONING.json') -Raw).Contains($tokens.parent)) 'Manifest contains hashes only'
    $repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    Reject { Assert-BootstrapExternalPath (Join-Path $repo 'out/private.json') $repo } 'Private config cannot enter repository'
    $private=Join-Path $root 'private.json'
    @{public=$manifest;parentToken=$tokens.parent;childToken=$tokens.child}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $private
    $loaded=Read-BootstrapPrivateConfigV232 $private $repo;Check ((Get-BootstrapHash $loaded.parentToken) -ceq $manifest.roles.parent.sha256) 'External private config validates without output'
    if($IsWindows) {
        $generated=Join-Path $root 'protected-generator'
        & (Join-Path $PSScriptRoot 'new-bootstrap-config-v232.ps1') -Directory $generated | Out-Null
        $fresh=Read-BootstrapPrivateConfigV232 (Join-Path $generated 'bootstrap.private.json') $repo
        Check ($fresh.parentToken -cne $fresh.childToken -and (Get-Acl -LiteralPath $generated).AreAccessRulesProtected) 'Generator uses distinct random capabilities and protected ACL'
        Reject { & (Join-Path $PSScriptRoot 'new-bootstrap-config-v232.ps1') -Directory $generated } 'Generator refuses overwrite'
        foreach($file in @('bootstrap.private.json','BOOTSTRAP-PROVISIONING.json')) { Remove-Item -LiteralPath (Join-Path $generated $file) }
        Remove-Item -LiteralPath $generated
    }
    Write-Output "PASS: $count bootstrap build/signing-boundary regressions. Ephemeral random test inputs only; no signing or production provisioning."
} finally {
    $tokens=$null;$loaded=$null;$fresh=$null
    foreach($file in @('Family-Parent-v2.3.2-unsigned.apk','Family-Child-v2.3.2-unsigned.apk','BOOTSTRAP-PROVISIONING.json','private.json')) { $path=Join-Path $root $file;if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path} }
    # Exact files only; no recursive deletion of computed paths.
    if(@(Get-ChildItem -LiteralPath $root).Count -eq 0) { Remove-Item -LiteralPath $root }
}
