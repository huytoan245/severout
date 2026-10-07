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

function Get-ReleaseBuildToolsCandidate {
    param([string]$Directory, [string]$Version)
    $missing = [Collections.Generic.List[string]]::new()
    $aapt2 = Join-Path $Directory 'aapt2.exe'
    $zipalign = Join-Path $Directory 'zipalign.exe'
    foreach ($tool in @(@{ Name = 'aapt2.exe'; Path = $aapt2 }, @{ Name = 'zipalign.exe'; Path = $zipalign })) {
        if (-not (Test-Path -LiteralPath $tool.Path -PathType Leaf)) { $missing.Add("Missing $($tool.Name): $($tool.Path)") }
    }
    # SDK's apksigner.bat wraps a JAR at one of these locations. Invoke the JAR
    # directly, avoiding cmd.exe parsing and any dependency on the caller's PATH.
    $jarPaths = @((Join-Path $Directory 'lib/apksigner.jar'), (Join-Path $Directory 'apksigner.jar'), (Join-Path $Directory '../framework/apksigner.jar'))
    $jar = $jarPaths | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $jar) { $missing.Add('Missing usable apksigner.jar: ' + ($jarPaths -join ', ') + '. apksigner.bat alone is not an implementation.') }
    $properties = Join-Path $Directory 'source.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $revision = [regex]::Match((Get-Content -LiteralPath $properties -Raw), '(?m)^Pkg.Revision\s*=\s*([^\r\n]+)')
        if (-not $revision.Success -or $revision.Groups[1].Value.Trim() -cne $Version) { $missing.Add("Broken/preview package metadata: $properties (expected Pkg.Revision=$Version)") }
    }
    [pscustomobject]@{ Version = $Version; Directory = $Directory; Aapt2 = $aapt2; Zipalign = $zipalign; ApkSignerJar = $jar; Problems = @($missing.ToArray()); Complete = ($missing.Count -eq 0) }
}

function Resolve-ReleaseBuildTools {
    param([string]$AndroidSdk, [string]$BuildToolsVersion, [string]$ProjectDirectory, [string]$MetadataPath)
    $sdk = [IO.Path]::GetFullPath($AndroidSdk)
    $base = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $base -PathType Container)) { throw "Missing SDK build-tools directory: $base" }
    if ($BuildToolsVersion) {
        if ($BuildToolsVersion -notmatch '^\d+\.\d+\.\d+$') { throw "Explicit build-tools version must be stable: $BuildToolsVersion" }
        $selected = Get-ReleaseBuildToolsCandidate (Join-Path $base $BuildToolsVersion) $BuildToolsVersion
        if (-not $selected.Complete) { throw ("Requested build-tools $BuildToolsVersion failed:`n" + ($selected.Problems -join "`n")) }
        $reason = 'explicit version'
    } else {
        $projectVersions = @()
        if ($ProjectDirectory -and $MetadataPath -and (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) {
            $metadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
            $valid = $metadata.gradleFileHashes -and $metadata.modules
            foreach ($property in $metadata.gradleFileHashes.PSObject.Properties) {
                $file = Join-Path $ProjectDirectory $property.Name
                if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $property.Value) { $valid = $false }
            }
            if ($valid) { $projectVersions = @($metadata.modules.PSObject.Properties.Value | Sort-Object -Unique) }
            else { Write-Host "Ignoring stale Android build-tools metadata: $MetadataPath" }
        }
        # Also honor an explicit version in Android build files if no captured
        # evaluated AGP metadata is available. compileSdk is not buildToolsVersion.
        if ($projectVersions.Count -eq 0 -and $ProjectDirectory -and (Test-Path -LiteralPath $ProjectDirectory)) {
            $versions = foreach ($file in Get-ChildItem -LiteralPath $ProjectDirectory -Filter 'build.gradle*' -File -Recurse | Where-Object { $_.FullName -notmatch '[\\/](build|\.gradle)[\\/]' }) {
                $matches = [regex]::Matches((Get-Content -LiteralPath $file.FullName -Raw), '(?m)^\s*buildToolsVersion\s*(?:=|\()?\s*["''](\d+\.\d+\.\d+)["'']')
                foreach ($match in $matches) { $match.Groups[1].Value }
            }
            $projectVersions = @($versions | Sort-Object -Unique)
        }
        $selected = $null
        $problems = [Collections.Generic.List[string]]::new()
        foreach ($version in $projectVersions | Where-Object { $_ -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_ } -Descending) {
            $candidate = Get-ReleaseBuildToolsCandidate (Join-Path $base $version) $version
            if ($candidate.Complete) { $selected = $candidate; $reason = 'evaluated/project Android version'; break }
            foreach ($problem in $candidate.Problems) { $problems.Add($problem) }
        }
        if (-not $selected) {
            foreach ($directory in Get-ChildItem -LiteralPath $base -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending) {
                $candidate = Get-ReleaseBuildToolsCandidate $directory.FullName $directory.Name
                if ($candidate.Complete) { $selected = $candidate; $reason = 'highest complete installed stable version'; break }
                foreach ($problem in $candidate.Problems) { $problems.Add($problem) }
            }
        }
        if (-not $selected) { throw ("No complete stable build-tools under $base.`n" + (($problems | Select-Object -Unique) -join "`n")) }
    }
    $selected | Add-Member -NotePropertyName Reason -NotePropertyValue $reason
    Write-Host "Build-tools selected: $($selected.Version) ($reason)"
    Write-Host "aapt2: $($selected.Aapt2)"
    Write-Host "zipalign: $($selected.Zipalign)"
    Write-Host "apksigner: $($selected.ApkSignerJar)"
    $selected
}

function Resolve-ReleaseSigningEnvironment {
    param([string]$AndroidSdk, [string]$JavaHome, [string]$BuildToolsVersion, [string]$ProjectDirectory, [string]$MetadataPath)
    foreach ($name in @('java.exe', 'keytool.exe')) {
        $path = Join-Path $JavaHome "bin/$name"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing $name`: $path" }
    }
    Resolve-ReleaseBuildTools $AndroidSdk $BuildToolsVersion $ProjectDirectory $MetadataPath
}

function Assert-ReleaseArchiveReadable {
    param([string]$ArchivePath, [switch]$Apk)
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $buffer = [byte[]]::new(1024 * 1024)
        foreach ($entry in $archive.Entries) {
            if (-not $names.Add($entry.FullName)) { throw "Duplicate ZIP entry: $($entry.FullName)" }
            $stream = $entry.Open()
            try {
                $length = 0L
                while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) { $length += $count }
                if ($length -ne $entry.Length) { throw 'ZIP entry decompressed length mismatch.' }
            } finally { $stream.Dispose() }
        }
        if ($Apk -and (-not $names.Contains('AndroidManifest.xml') -or -not $names.Contains('classes.dex'))) { throw 'APK manifest/classes missing.' }
    } finally { $archive.Dispose() }
}

function Get-ReleaseNativeApkInput {
    param([string]$Apk)
    $full = [IO.Path]::GetFullPath($Apk)
    if ($full -notmatch '[^\x00-\x7F]') { return [pscustomobject]@{ Path = $full; Stage = $null } }
    # Windows zipalign uses a narrow filename API. The same bytes pass at an
    # ASCII path but fail at this project's Vietnamese path. .NET copies safely.
    $temp = [IO.Path]::GetTempPath()
    if ($temp -match '[^\x00-\x7F]') { throw "Native APK staging requires an ASCII TEMP directory: $temp" }
    $stage = Join-Path $temp ('family-native-apk-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    $copy = Join-Path $stage 'input.apk'
    try {
        Copy-Item -LiteralPath $full -Destination $copy
        if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash) { throw 'Native staging APK differs from original bytes.' }
        [pscustomobject]@{ Path = $copy; Stage = $stage }
    } catch {
        if (Test-Path -LiteralPath $copy) { Remove-Item -LiteralPath $copy }
        [IO.Directory]::Delete($stage)
        throw
    }
}

function Remove-ReleaseNativeApkInput {
    param($InputFile)
    if ($InputFile.Stage) {
        Remove-Item -LiteralPath $InputFile.Path
        # Nonrecursive: fails safely if an unexpected file appeared.
        [IO.Directory]::Delete($InputFile.Stage)
    }
}

function Assert-ReleaseApkIdentity {
    param([string]$Badging, [string]$Package, [string]$Version = '2.3.0', [int]$VersionCode = 34)
    if (($Version -ceq '2.3.0' -and $VersionCode -ne 34) -or ($Version -ceq '2.3.1' -and $VersionCode -ne 35) -or ($Version -ceq '2.3.2' -and $VersionCode -ne 36) -or $Version -cnotin @('2.3.0','2.3.1','2.3.2')) { throw 'Unsupported release version/code pair.' }
    $match = [regex]::Match($Badging, "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (-not $match.Success -or $match.Groups[1].Value -cne $Package -or $match.Groups[2].Value -cne [string]$VersionCode -or $match.Groups[3].Value -cne $Version) { throw 'APK package/version gate failed.' }
}

function Assert-ReleaseApkCertificate {
    param([string]$Verification, [string]$Expected)
    $certificates = [regex]::Matches($Verification, '(?m)^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$')
    if ($certificates.Count -ne 1 -or $certificates[0].Groups[1].Value.ToLowerInvariant() -cne $Expected) { throw 'APK certificate differs from the sole pinned release signer.' }
}

function Test-ReleaseApk {
    param([string]$Apk, [string]$Package, [string]$Java, [string]$BuildTools, [string]$Expected, [string]$ApkSignerJar = (Join-Path $BuildTools 'lib/apksigner.jar'), [string]$Version = '2.3.0', [int]$VersionCode = 34)
    Assert-ReleaseArchiveReadable $Apk -Apk
    $inputFile = Get-ReleaseNativeApkInput $Apk
    try {
    $nativeApk = $inputFile.Path
    $identity = Invoke-ReleaseTool (Join-Path $BuildTools 'aapt2.exe') @('dump', 'badging', $nativeApk)
    if ($identity.ExitCode -ne 0) { throw 'APK badging failed.' }
    Assert-ReleaseApkIdentity $identity.Output $Package $Version $VersionCode
    $verify = Invoke-ReleaseTool $Java @('-jar', $ApkSignerJar, 'verify', '--verbose', '--print-certs', $nativeApk)
    if ($verify.ExitCode -ne 0) { throw 'apksigner verify failed; output not promoted.' }
    Assert-ReleaseApkCertificate $verify.Output $Expected
    $alignment = Invoke-ReleaseTool (Join-Path $BuildTools 'zipalign.exe') @('-c', '-P', '16', '4', $nativeApk)
    if ($alignment.ExitCode -ne 0) { throw '16KiB zipalign verification failed; output not promoted.' }
    [pscustomobject]@{ Package = $Package; VersionName = $Version; VersionCode = $VersionCode; CertificateSha256 = $Expected; Signature = 'PASS'; Zipalign = 'PASS'; Integrity = 'PASS (all ZIP entries readable and APK signature verified)'; Sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant(); Verification = $verify.Output }
    } finally { Remove-ReleaseNativeApkInput $inputFile }
}
