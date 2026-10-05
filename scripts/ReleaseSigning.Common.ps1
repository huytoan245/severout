#requires -Version 7.2
# Public identity only. Never change this to recover a failed password/inspection.
$script:ReleaseAlias = 'family-location-release-2026'
$script:ReleaseFingerprint = '62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f'

function Invoke-ReleaseTool {
    param([string]$Executable, [string[]]$Arguments)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # ArgumentList preserves dotted JVM options and paths with spaces on Windows.
    # Passwords are inherited via the environment, never passed as argument values.
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Unable to start signing tool.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult(); ErrorOutput = $stderr.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}

function Read-ReleasePassword {
    $secure = Read-Host 'Nhập mật khẩu keystore hiện có trực tiếp trên máy (không gửi vào chat)' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:FAMILY_LOCATION_KS_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
    if (-not $env:FAMILY_LOCATION_KS_PASSWORD) { throw 'Password input is empty.' }
}

function Assert-ReleaseKeystorePath {
    param([string]$KeystorePath, [string]$Repository)
    $path = [IO.Path]::GetFullPath($KeystorePath)
    $repo = [IO.Path]::GetFullPath($Repository)
    if ($path -eq $repo -or $path.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release keystore must remain outside the repository.' }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Existing release keystore missing. Recovery never creates a keystore.' }
}

function Get-ReleaseCertificateFingerprint {
    param([string]$Keytool, [string]$KeystorePath, [string]$Alias = $script:ReleaseAlias)
    if (-not (Test-Path -LiteralPath $Keytool -PathType Leaf)) { throw 'JDK keytool missing.' }
    $before = (Get-FileHash -LiteralPath $KeystorePath -Algorithm SHA256).Hash
    $certificate = Join-Path ([IO.Path]::GetTempPath()) ('family-location-public-cert-' + [guid]::NewGuid().ToString('N') + '.der')
    try {
        $options = @('-J-Duser.language=en', '-J-Duser.country=US')
        $listing = Invoke-ReleaseTool $Keytool ($options + @('-list', '-v', '-keystore', $KeystorePath, '-alias', $Alias, '-storepass:env', 'FAMILY_LOCATION_KS_PASSWORD'))
        if ($listing.ExitCode -ne 0) { throw "Read-only keytool inspection failed (exit $($listing.ExitCode)); pin unchanged. Check the password/alias locally." }
        $aliasMatch = [regex]::Match($listing.Output, '(?m)^Alias name:\s*([^\r\n]+)\s*$')
        if (-not $aliasMatch.Success -or $aliasMatch.Groups[1].Value.Trim() -cne $Alias -or $listing.Output -notmatch 'Entry type:\s*PrivateKeyEntry') { throw 'Exact release alias/private-key entry verification failed; pin unchanged.' }
        $export = Invoke-ReleaseTool $Keytool ($options + @('-exportcert', '-keystore', $KeystorePath, '-alias', $Alias, '-storepass:env', 'FAMILY_LOCATION_KS_PASSWORD', '-file', $certificate))
        if ($export.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $certificate -PathType Leaf)) { throw 'Public certificate export failed; pin unchanged.' }
        # Hash the certificate DER, not the keystore and not localized keytool text.
        (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally {
        if (Test-Path -LiteralPath $certificate) { Remove-Item -LiteralPath $certificate }
        if ((Get-FileHash -LiteralPath $KeystorePath -Algorithm SHA256).Hash -ne $before) { throw 'Keystore bytes changed during inspection; stop. No pin allowed.' }
    }
}

function Set-ReleaseSignerPin {
    param([string]$Document, [string]$Fingerprint)
    if ($Fingerprint -cne $script:ReleaseFingerprint) { throw 'Certificate does not match the user-confirmed v230 fingerprint; pin unchanged.' }
    $text = Get-Content -LiteralPath $Document -Raw
    $pins = [regex]::Matches($text, 'Pinned signer SHA-256: `([^`]+)`')
    if ($pins.Count -ne 1 -or $pins[0].Groups[1].Value -cnotin @('NOT_CREATED', $Fingerprint)) { throw 'Signer pin is missing/ambiguous/different; replacement prohibited.' }
    $text = $text.Replace('Pinned signer SHA-256: `NOT_CREATED`', ('Pinned signer SHA-256: `' + $Fingerprint + '`'))
    $text = [regex]::Replace($text, '(?m)^Status:.*$', 'Status: existing signer verified locally and pinned. APK signing/verification is a separate gate.')
    [IO.File]::WriteAllText($Document, $text, [Text.UTF8Encoding]::new($false))
}

function Get-ReleaseSignerPin {
    param([string]$Document)
    $pins = [regex]::Matches((Get-Content -LiteralPath $Document -Raw), 'Pinned signer SHA-256: `([^`]+)`')
    if ($pins.Count -ne 1 -or $pins[0].Groups[1].Value -cne $script:ReleaseFingerprint) { throw 'Expected existing signer is not pinned. Run recover-release-signer.ps1 locally; never regenerate it or send passwords into chat.' }
    $script:ReleaseFingerprint
}

function Assert-ReleaseApkIdentity {
    param([string]$Badging, [string]$Package)
    $match = [regex]::Match($Badging, "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $match.Success -or $match.Groups[1].Value -cne $Package -or $match.Groups[2].Value -cne '34' -or $match.Groups[3].Value -cne '2.3.0') { throw 'APK package/version gate failed.' }
}

function Assert-ReleaseApkCertificate {
    param([string]$Verification, [string]$Expected)
    $certificates = [regex]::Matches($Verification, '(?m)^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$')
    if ($certificates.Count -ne 1 -or $certificates[0].Groups[1].Value.ToLowerInvariant() -cne $Expected) { throw 'APK certificate differs from the sole pinned release signer.' }
}

function Test-ReleaseApk {
    param([string]$Apk, [string]$Package, [string]$Java, [string]$BuildTools, [string]$Expected)
    $identity = Invoke-ReleaseTool (Join-Path $BuildTools 'aapt2.exe') @('dump', 'badging', $Apk)
    if ($identity.ExitCode -ne 0) { throw 'APK badging failed.' }
    Assert-ReleaseApkIdentity $identity.Output $Package
    $verify = Invoke-ReleaseTool $Java @('-jar', (Join-Path $BuildTools 'lib\apksigner.jar'), 'verify', '--verbose', '--print-certs', $Apk)
    if ($verify.ExitCode -ne 0) { throw 'apksigner verify failed; output not promoted.' }
    Assert-ReleaseApkCertificate $verify.Output $Expected
    $alignment = Invoke-ReleaseTool (Join-Path $BuildTools 'zipalign.exe') @('-c', '-P', '16', '4', $Apk)
    if ($alignment.ExitCode -ne 0) { throw '16KiB zipalign verification failed; output not promoted.' }
    [pscustomobject]@{ Package = $Package; VersionName = '2.3.0'; VersionCode = 34; CertificateSha256 = $Expected; Signature = 'PASS'; Zipalign = 'PASS'; Sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant(); Verification = $verify.Output }
}
