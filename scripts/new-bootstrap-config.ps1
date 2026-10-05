#requires -Version 7.2
param([Parameter(Mandatory=$true)][string]$Directory)
. (Join-Path $PSScriptRoot 'Bootstrap.Common.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory=Assert-BootstrapExternalPath $Directory $repo
New-BootstrapPrivateDirectory $directory
function New-Capability { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_') }
$parent=New-Capability; do { $child=New-Capability } while ($child -ceq $parent)
$expiry=[DateTimeOffset]::UtcNow.AddDays(7).ToUnixTimeMilliseconds()
$public=@{familyId='family-01';deviceId='child-01';versionName='2.3.1';versionCode=35;roles=@{parent=@{sha256=(Get-BootstrapHash $parent);expiresAt=$expiry};child=@{sha256=(Get-BootstrapHash $child);expiresAt=$expiry}}}
$private=@{parentToken=$parent;childToken=$child;public=$public}
[IO.File]::WriteAllText((Join-Path $directory 'bootstrap.private.json'),($private|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $directory 'BOOTSTRAP-PROVISIONING.json'),($public|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$private=$null; $parent=$null; $child=$null
Write-Output 'Two distinct 256-bit capabilities saved outside Git under a protected local ACL. No tokens printed. No signing, keystore changes or deployment.'
