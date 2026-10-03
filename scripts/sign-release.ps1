param(
    [string]$KeystorePath = 'C:\Users\Admin\Documents\FamilyLocation-Signing\Family-Location-Release-2026.jks',
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$UnsignedDirectory = (Join-Path $PSScriptRoot '..\out\v230'),
    [string]$Version = '2.3.0'
)
$ErrorActionPreference = 'Stop'
if ($Version -ne '2.3.0') { throw 'This gate requires v2.3.0/code34. Update the gate explicitly for a future release.' }
$docs = Join-Path $PSScriptRoot '..\docs\RELEASE_SIGNING_V230.md'
$match = [regex]::Match((Get-Content -LiteralPath $docs -Raw),'Pinned signer SHA-256: `([a-f0-9]{64})`')
if (-not $match.Success) { throw 'New signer not yet created/pinned. Run create-release-keystore.ps1 locally; never send the password into chat.' }
$expected = $match.Groups[1].Value
if (-not (Test-Path -LiteralPath $KeystorePath)) { throw 'New release keystore missing.' }
$java = Join-Path $JavaHome 'bin\java.exe'
$buildTools = Join-Path $AndroidSdk 'build-tools\36.0.0'
$signer = Join-Path $buildTools 'lib\apksigner.jar'
$align = Join-Path $buildTools 'zipalign.exe'
$aapt = Join-Path $buildTools 'aapt2.exe'
$stage = Join-Path $env:TEMP ('family-location-sign-' + [guid]::NewGuid().ToString('N'))
$previous = $env:FAMILY_LOCATION_KS_PASSWORD
try {
    if (-not $env:FAMILY_LOCATION_KS_PASSWORD) {
        $secure = Read-Host 'Nhập mật khẩu signer mới trực tiếp trên máy (không gửi vào chat)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:FAMILY_LOCATION_KS_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
    }
    New-Item -ItemType Directory -Path $stage | Out-Null
    foreach ($app in @('Parent','Child')) {
        $inputApk = Join-Path $UnsignedDirectory "Family-$app-v$Version-unsigned.apk"
        Copy-Item -LiteralPath $inputApk -Destination (Join-Path $stage "$app-unsigned.apk")
        $package = 'com.family.' + $app.ToLowerInvariant()
        $badging = & $aapt dump badging (Join-Path $stage "$app-unsigned.apk")
        if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "name='$package' versionCode='34' versionName='2.3.0'") { throw 'APK package/version gate failed.' }
        & $align -P 16 -f 4 (Join-Path $stage "$app-unsigned.apk") (Join-Path $stage "$app-aligned.apk")
        if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
        & $java -jar $signer sign --ks $KeystorePath --ks-key-alias family-location-release-2026 --ks-pass env:FAMILY_LOCATION_KS_PASSWORD --key-pass env:FAMILY_LOCATION_KS_PASSWORD --out (Join-Path $stage "$app-signed.apk") (Join-Path $stage "$app-aligned.apk")
        if ($LASTEXITCODE -ne 0) { throw 'Signing failed; output not promoted.' }
        $verify = & $java -jar $signer verify --verbose --print-certs (Join-Path $stage "$app-signed.apk") 2>&1
        if ($LASTEXITCODE -ne 0 -or ($verify -join "`n") -notmatch "certificate SHA-256 digest: $expected") { throw 'Signature differs from pinned new signer; output not promoted.' }
        $verify | Out-File (Join-Path $stage "$app-signature.txt") -Encoding utf8
        & $align -c -P 16 4 (Join-Path $stage "$app-signed.apk")
        if ($LASTEXITCODE -ne 0) { throw 'Signed alignment failed; output not promoted.' }
    }
    # Promote only after BOTH apps passed with the same pinned certificate.
    foreach ($app in @('Parent','Child')) {
        Copy-Item -LiteralPath (Join-Path $stage "$app-signed.apk") -Destination (Join-Path $UnsignedDirectory "Family-$app-v$Version-Installable.apk")
        Copy-Item -LiteralPath (Join-Path $stage "$app-signature.txt") -Destination (Join-Path $UnsignedDirectory "$app-signature.txt")
    }
    $sums = foreach ($apk in Get-ChildItem -LiteralPath $UnsignedDirectory -Filter '*Installable.apk') { (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $apk.Name }
    $sumPath = Join-Path $UnsignedDirectory 'SIGNED-SHA256SUMS.txt'
    $sums | Out-File $sumPath -Encoding utf8
    $zipItems = @((Join-Path $UnsignedDirectory 'Family-Parent-v2.3.0-Installable.apk'),(Join-Path $UnsignedDirectory 'Family-Child-v2.3.0-Installable.apk'),$sumPath,$docs,(Join-Path $UnsignedDirectory 'Parent-signature.txt'),(Join-Path $UnsignedDirectory 'Child-signature.txt'))
    $release = Join-Path $UnsignedDirectory 'Family-Location-v2.3.0-Release.zip'
    Compress-Archive -LiteralPath $zipItems -DestinationPath $release -Force
    Get-FileHash -LiteralPath $release -Algorithm SHA256
    Write-Output 'Both APKs signed with pinned new signer and verified. Production/FCM/Samsung testing remains unverified. No app uninstall performed.'
} finally {
    $env:FAMILY_LOCATION_KS_PASSWORD = $previous
    # Only public APKs/certificate logs remain in the temporary staging directory.
}
