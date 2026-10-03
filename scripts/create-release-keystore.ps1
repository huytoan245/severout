param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$SigningDirectory = 'C:\Users\Admin\Documents\FamilyLocation-Signing'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory = [IO.Path]::GetFullPath($SigningDirectory)
if ($directory.StartsWith($repo + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $directory -eq $repo) { throw 'Keystore directory must be outside the repository.' }
$gate = Join-Path $repo 'out\v230\AUTOMATED-GATE.json'
if (-not (Test-Path -LiteralPath $gate) -or (Get-Content -LiteralPath $gate -Raw | ConvertFrom-Json).status -ne 'PASS') { throw 'Run and review v230 automated build/test gate first.' }
$docs = Join-Path $repo 'docs\RELEASE_SIGNING_V230.md'
if ((Get-Content -LiteralPath $docs -Raw) -notmatch 'Pinned signer SHA-256: `NOT_CREATED`') { throw 'Signer is already pinned. Do not generate a replacement.' }
$keystore = Join-Path $directory 'Family-Location-Release-2026.jks'
if (Test-Path -LiteralPath $keystore) { throw 'Keystore already exists; preserve it and review its public fingerprint instead of overwriting.' }
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK keytool missing.' }
$previous = $env:FAMILY_LOCATION_KS_PASSWORD
try {
    if (-not $env:FAMILY_LOCATION_KS_PASSWORD) {
        $secure = Read-Host 'Nhập mật khẩu keystore mới trên máy này (không gửi vào chat)' -AsSecureString
        $confirm = Read-Host 'Nhập lại mật khẩu' -AsSecureString
        $a = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirm)
        try {
            $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($a)
            if ($password -ne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) -or $password.Length -lt 12) { throw 'Passwords must match and have at least 12 characters.' }
            $env:FAMILY_LOCATION_KS_PASSWORD = $password
        } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($a); [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b); $password = $null; $secure.Dispose(); $confirm.Dispose() }
    } elseif ($env:FAMILY_LOCATION_KS_PASSWORD.Length -lt 12) { throw 'Use at least 12 password characters.' }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    & $keytool -genkeypair -keystore $keystore -storetype JKS -alias family-location-release-2026 -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Family Location Release 2026' -storepass:env FAMILY_LOCATION_KS_PASSWORD -keypass:env FAMILY_LOCATION_KS_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Keystore creation failed; no signer pinned.' }
    $listing = & $keytool -J-Duser.language=en -J-Duser.country=US -list -v -keystore $keystore -alias family-location-release-2026 -storepass:env FAMILY_LOCATION_KS_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Certificate inspection failed; no signer pinned.' }
    $match = [regex]::Match(($listing -join "`n"),'SHA256:\s*([0-9A-F:]{95})')
    if (-not $match.Success) { throw 'SHA-256 certificate fingerprint not found.' }
    $fingerprint = $match.Groups[1].Value.Replace(':','').ToLowerInvariant()
    $text = Get-Content -LiteralPath $docs -Raw
    $text = $text.Replace('Pinned signer SHA-256: `NOT_CREATED`',('Pinned signer SHA-256: `' + $fingerprint + '`'))
    $text = $text.Replace('Status: no keystore/password input available.','Status: signer created locally; public certificate fingerprint pinned. APK signature verification still required.')
    [IO.File]::WriteAllText($docs,$text,[Text.UTF8Encoding]::new($false))
    Write-Output "New public certificate SHA-256: $fingerprint"
    Write-Output 'Keystore created locally; keep it/password secure. No app uninstall or production deployment performed.'
} finally { $env:FAMILY_LOCATION_KS_PASSWORD = $previous }
