#requires -Version 7.2
param(
 [Parameter(Mandatory)][string]$CaseDirectory,
 [string]$Directory=(Join-Path $PSScriptRoot '..\out\v233-parent-recovery'),
 [string]$AndroidSdk='C:\Users\Admin\AppData\Local\Android\Sdk',
 [string]$JavaHome='C:\Users\Admin\.codex\tools\family-location\jdk17\jdk-17.0.20.1+1',
 [string]$KeystorePath='C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks',
 [switch]$CheckEnvironmentOnly
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Assert-ReleaseKeystorePath $KeystorePath $repo
$expected=Get-ReleaseSignerPin (Join-Path $repo 'docs/RELEASE_SIGNING_V233.md')
$gate=Get-Content -LiteralPath (Join-Path $Directory 'PARENT-RECOVERY-GATE.json') -Raw|ConvertFrom-Json
if($gate.status-cne'PASS'-or$gate.ReleaseMode-cne'PARENT_RECOVERY'-or$gate.VersionName-cne'2.3.3'-or$gate.VersionCode-ne37-or$gate.productionApprovalIssued-ne$false){throw 'Separate tested Parent Recovery gate required'}
foreach($property in $gate.inputHashes.PSObject.Properties){if((Get-FileHash -LiteralPath (Join-Path $repo ('appsrc/'+$property.Name)) -Algorithm SHA256).Hash.ToLowerInvariant()-cne$property.Value){throw 'Recovery source changed; rebuild before signing'}}
$public=(Get-Content -LiteralPath (Join-Path $CaseDirectory 'operator-public.txt') -Raw).Trim()
$publicHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($public))).ToLowerInvariant()
if($publicHash-cne$gate.operatorPublicKeyHash){throw 'Operator report key does not match tested recovery APK'}
$apk=Join-Path $Directory 'Family-Parent-v2.3.3-Recovery-unsigned.apk'
if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()-cne$gate.apkSha256){throw 'Recovery APK hash mismatch'}
$tools=Resolve-ReleaseSigningEnvironment $AndroidSdk $JavaHome '' (Join-Path $repo 'appsrc') (Join-Path $Directory 'ANDROID-BUILD-TOOLS.json')
$native=Get-ReleaseNativeApkInput $apk
try{
 Assert-ReleaseArchiveReadable $native.Path -Apk
 $identity=Invoke-ReleaseTool $tools.Aapt2 @('dump','badging',$native.Path)
 if($identity.ExitCode-ne0){throw 'Recovery aapt2 failed'}
 Assert-ReleaseApkIdentity $identity.Output 'com.family.parent' '2.3.3' 37
 if($identity.Output-cmatch'application-debuggable'-or$identity.Output-cnotmatch"launchable-activity: name='com\.family\.parent\.RecoveryActivity'"){throw 'Non-debuggable Recovery launcher required'}
 $xml=Invoke-ReleaseTool $tools.Aapt2 @('dump','xmltree','--file','AndroidManifest.xml',$native.Path)
 foreach($flag in @('family.parent_recovery','family.update_in_place')){if($xml.ExitCode-ne0-or$xml.Output-cnotmatch([regex]::Escape($flag)+'[\s\S]{0,240}android:value[^\r\n]*(?:0xffffffff|true)')){throw 'Recovery manifest guards missing'}}
 if($xml.Output-cnotmatch'RecoveryActivity'){throw 'Recovery launcher missing'}
 $alignment=Invoke-ReleaseTool $tools.Zipalign @('-c','-P','16','4',$native.Path)
 if($alignment.ExitCode-ne0){throw 'Recovery unsigned 16KiB alignment failed'}
 $java=Join-Path $JavaHome 'bin/java.exe';$keytool=Join-Path $JavaHome 'bin/keytool.exe'
 if((Invoke-ReleaseTool $java @('-jar',$tools.ApkSignerJar,'version')).ExitCode-ne0){throw 'Recovery apksigner unavailable'}
 if($CheckEnvironmentOnly){Write-Output 'Parent Recovery package/version/operator key/manifest/16KiB preflight PASS. No password, JKS read, signing or identity changes.';return}
 $output=Join-Path $Directory 'Family-Parent-v2.3.3-Recovery-Installable.apk'
 if(Test-Path -LiteralPath $output){throw 'Preserve existing signed recovery APK; validate it instead of overwriting'}
 $stage=Join-Path ([IO.Path]::GetTempPath()) ('family-parent-recovery-sign-'+[guid]::NewGuid().ToString('N'))
 $previous=$env:FAMILY_LOCATION_KS_PASSWORD
 try{
  Read-ReleasePassword
  if((Get-ReleaseCertificateFingerprint $keytool $KeystorePath)-cne$expected){throw 'Existing signer mismatch; nothing signed'}
  New-Item -ItemType Directory -Path $stage|Out-Null
  $aligned=Join-Path $stage 'Parent-aligned.apk';$signed=Join-Path $stage 'Parent-signed.apk'
  if((Invoke-ReleaseTool $tools.Zipalign @('-P','16','-f','4',$native.Path,$aligned)).ExitCode-ne0){throw 'Recovery zipalign failed'}
  if((Invoke-ReleaseTool $java @('-jar',$tools.ApkSignerJar,'sign','--ks',$KeystorePath,'--ks-key-alias',$script:ReleaseAlias,'--ks-pass','env:FAMILY_LOCATION_KS_PASSWORD','--key-pass','env:FAMILY_LOCATION_KS_PASSWORD','--v4-signing-enabled','false','--out',$signed,$aligned)).ExitCode-ne0){throw 'Recovery signing failed; no publication'}
  $checked=Test-ReleaseApk $signed 'com.family.parent' $java $tools.Directory $expected $tools.ApkSignerJar '2.3.3' 37
  Copy-Item -LiteralPath $signed -Destination $output
  $checked|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $Directory 'PARENT-RECOVERY-SIGNATURE.json') -Encoding utf8
  Set-Content -LiteralPath (Join-Path $Directory 'RECOVERY-SIGNED-SHA256SUMS.txt') -Value ($checked.Sha256+'  '+[IO.Path]::GetFileName($output)) -Encoding utf8
  Write-Output 'Parent Recovery signing PASS. Same release signer; no installation, new UID/key, rebind or production deployment.'
 }finally{
  $env:FAMILY_LOCATION_KS_PASSWORD=$previous
  $resolved=[IO.Path]::GetFullPath($stage);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
  if($resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase)-and[IO.Path]::GetFileName($resolved).StartsWith('family-parent-recovery-sign-')-and(Test-Path -LiteralPath $resolved)){Remove-Item -LiteralPath $resolved -Recurse -Force}
 }
}finally{Remove-ReleaseNativeApkInput $native}
