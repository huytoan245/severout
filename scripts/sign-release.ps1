param(
    [Parameter(Mandatory=$true)][string]$KeystorePath,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$UnsignedDirectory = (Join-Path $PSScriptRoot '..\out')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeystorePath)) { throw 'Existing release keystore not found. Do not generate a replacement signer.' }
if (-not $env:FAMILY_LOCATION_KS_PASSWORD) { throw 'Set FAMILY_LOCATION_KS_PASSWORD locally through your secure signing process; do not paste it into chat.' }
$expected = '4675c26756f33870024ea10a00ec300d1329d37bb3c9f169a25e9f4a6e5bdbbd'
$java = Join-Path $JavaHome 'bin\java.exe'
$signer = Join-Path $AndroidSdk 'build-tools\36.0.0\lib\apksigner.jar'
$align = Join-Path $AndroidSdk 'build-tools\36.0.0\zipalign.exe'
$stage = Join-Path $env:TEMP ('family-location-sign-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
foreach ($app in @('Parent','Child')) {
    $inputApk = Join-Path $UnsignedDirectory "Family-$app-v2.2.10-unsigned.apk"
    Copy-Item -LiteralPath $inputApk -Destination (Join-Path $stage "$app-unsigned.apk")
    & $align -p -f 4 (Join-Path $stage "$app-unsigned.apk") (Join-Path $stage "$app-aligned.apk")
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    $signArgs = @('-jar', $signer, 'sign', '--ks', $KeystorePath, '--ks-pass', 'env:FAMILY_LOCATION_KS_PASSWORD')
    if ($env:FAMILY_LOCATION_KEY_PASSWORD) { $signArgs += @('--key-pass', 'env:FAMILY_LOCATION_KEY_PASSWORD') }
    $signArgs += @('--out', (Join-Path $stage "$app-signed.apk"), (Join-Path $stage "$app-aligned.apk"))
    & $java @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
    $verify = & $java -jar $signer verify --verbose --print-certs (Join-Path $stage "$app-signed.apk") 2>&1
    if ($LASTEXITCODE -ne 0 -or -not ($verify -match "certificate SHA-256 digest: $expected")) { throw 'Signature verification failed or signer differs from v2.2.1+; signed output was not promoted.' }
    & $align -c -p 4 (Join-Path $stage "$app-signed.apk")
    if ($LASTEXITCODE -ne 0) { throw 'Signed zipalign verification failed' }
    Copy-Item -LiteralPath (Join-Path $stage "$app-signed.apk") -Destination (Join-Path $UnsignedDirectory "Family-$app-v2.2.10-Installable.apk")
}
Get-ChildItem -LiteralPath $UnsignedDirectory -Filter '*Installable.apk' | Get-FileHash -Algorithm SHA256 | Format-Table -AutoSize
Write-Output 'Signer verified. Device install/update and Samsung endurance still require actual testing.'
