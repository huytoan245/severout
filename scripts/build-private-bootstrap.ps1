#requires -Version 7.2
param(
    [Parameter(Mandatory=$true)][string]$Config,
    [Parameter(Mandatory=$true)][string]$BuildDirectory,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [Parameter(Mandatory=$true)][string]$Gradle,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$Python
)
. (Join-Path $PSScriptRoot 'Bootstrap.Common.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$configData=Read-BootstrapPrivateConfig $Config $repo
$build=Assert-BootstrapExternalPath $BuildDirectory $repo
if($build -match '[^\x00-\x7F]') { throw 'Private Windows Gradle build must use an ASCII path.' }
$gate=Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'AUTOMATED-GATE.json') -Raw | ConvertFrom-Json
if($gate.status -cne 'PASS' -or -not $gate.testedSourceHashes) { throw 'Run the reviewed source/test gate before preparing a private build.' }
foreach($property in $gate.testedSourceHashes.PSObject.Properties) {
    if((Get-FileHash -LiteralPath (Join-Path $repo $property.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $property.Value) { throw 'Source changed since review evidence. Rerun validation before a private build.' }
}
$tracked=& git -C $repo ls-files -- appsrc cloudflare-wake scripts
if($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tested source.' }
$expectedPaths=@($tracked | Where-Object { $_ -notmatch '(^|/)docs/' -and [IO.Path]::GetFileName($_) -cne 'README.md' } | Sort-Object)
$recordedPaths=@($gate.testedSourceHashes.PSObject.Properties.Name | Sort-Object)
if(($expectedPaths -join "`n") -cne ($recordedPaths -join "`n")) { throw 'Tested source file set changed; rerun review validation.' }
New-BootstrapPrivateDirectory $build
$previous=@{}
foreach($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','FAMILY_LOCATION_PARENT_BOOTSTRAP','FAMILY_LOCATION_CHILD_BOOTSTRAP')) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $files=& git -C $repo ls-files -- appsrc
    if($LASTEXITCODE -ne 0) { throw 'Cannot enumerate canonical tracked Android source.' }
    foreach($file in $files) {
        $relative=$file.Substring('appsrc/'.Length)
        $target=Join-Path $build $relative
        [IO.Directory]::CreateDirectory((Split-Path $target)) | Out-Null
        Copy-Item -LiteralPath (Join-Path $repo $file) -Destination $target
    }
    [IO.File]::WriteAllText((Join-Path $build 'local.properties'),('sdk.dir='+$AndroidSdk.Replace('\','/')+"`n"),[Text.UTF8Encoding]::new($false))
    $env:JAVA_HOME=$JavaHome;$env:ANDROID_HOME=$AndroidSdk;$env:ANDROID_SDK_ROOT=$AndroidSdk
    $env:FAMILY_LOCATION_PARENT_BOOTSTRAP=$configData.parentToken;$env:FAMILY_LOCATION_CHILD_BOOTSTRAP=$configData.childToken
    $evidence=Join-Path $build 'evidence';[IO.Directory]::CreateDirectory($evidence) | Out-Null
    foreach($name in @('worker-tests-v231.log','runtime-v231.log','rules-v231.log','signing-regression-v231.log')) { Copy-Item -LiteralPath (Join-Path $EvidenceDirectory $name) -Destination $evidence }
    $log=Join-Path $evidence 'build-v231.log'
    $init=Join-Path $evidence 'capture-build-tools.gradle'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'capture-build-tools.gradle') -Destination $init
    Push-Location $build
    try {
        & $Gradle -I $init captureSigningBuildTools "-PfamilySigningMetadata=$(Join-Path $evidence 'v231-ANDROID-BUILD-TOOLS.json')" :core:coreSelfCheck :core:scenarioCheck :enrollment:testDebugUnitTest :child-app:testDebugUnitTest :parent-app:assembleRelease :child-app:assembleRelease :enrollment:lintRelease :parent-app:lintRelease :child-app:lintRelease --no-daemon --no-build-cache --no-configuration-cache --console=plain *> $log
        $buildExit=$LASTEXITCODE
    } finally { Pop-Location }
    # Never echo unfiltered Gradle errors or generated secret-bearing source.
    $safeLog=[IO.File]::ReadAllText($log).Replace($configData.parentToken,'[REDACTED]').Replace($configData.childToken,'[REDACTED]')
    [IO.File]::WriteAllText($log,$safeLog,[Text.UTF8Encoding]::new($false))
    if($buildExit -ne 0) { throw 'Private build failed. Inspect the redacted log locally; no signing performed.' }
    $output=Join-Path $build 'candidate'
    & $Python -X utf8 (Join-Path $PSScriptRoot 'prepare-v231-candidate.py') --build-root $build --evidence-root $evidence --output-directory $output --bootstrap-manifest (Join-Path (Split-Path $Config) 'BOOTSTRAP-PROVISIONING.json')
    if($LASTEXITCODE -ne 0) { throw 'Private candidate validation failed.' }
    Assert-BootstrapApkPair $output
    Write-Output 'Private role bootstrap injection and both APK hash scans PASS. Unsigned candidates only. STOP before signing/deploy for review.'
} finally {
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    $configData=$null
}
