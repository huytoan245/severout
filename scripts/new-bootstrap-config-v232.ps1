#requires -Version 7.2
param([Parameter(Mandatory=$true)][string]$Directory)
. (Join-Path $PSScriptRoot 'Bootstrap.V232.Common.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory=Assert-BootstrapExternalPath $Directory $repo
New-BootstrapPrivateDirectory $directory
function New-Capability { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_') }
$parent=New-Capability; do { $child=New-Capability } while ($child -ceq $parent)
$public=@{familyId='family-01';deviceId='child-01';versionName='2.3.2';versionCode=36;bootstrapMode='UNTIL_CONSUMED_OR_REVOKED';roles=@{parent=@{sha256=(Get-BootstrapHash $parent)};child=@{sha256=(Get-BootstrapHash $child)}}}
$private=@{parentToken=$parent;childToken=$child;public=$public}
[IO.File]::WriteAllText((Join-Path $directory 'bootstrap.private.json'),($private|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $directory 'BOOTSTRAP-PROVISIONING.json'),($public|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$private=$null; $parent=$null; $child=$null
Write-Output 'Two distinct 256-bit capabilities saved outside Git under a protected local ACL. No tokens printed. No signing, keystore changes or deployment.'
