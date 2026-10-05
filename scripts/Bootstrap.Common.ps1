#requires -Version 7.2
$ErrorActionPreference = 'Stop'
function Get-BootstrapHash([string]$Token) {
    [Convert]::ToBase64String([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Token))).TrimEnd('=').Replace('+','-').Replace('/','_')
}
function Assert-BootstrapExternalPath([string]$Path, [string]$Repository) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath($Repository).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ($full.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Private bootstrap files/builds must remain outside the repository.' }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Private bootstrap paths cannot traverse a symlink/junction.' }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }; $cursor = $parent
    }
    $full
}
function New-BootstrapPrivateDirectory([string]$Path) {
    if (-not $IsWindows) { throw 'Private bootstrap provisioning requires the local Windows host.' }
    if (Test-Path -LiteralPath $Path) { throw 'Refusing to overwrite an existing bootstrap directory.' }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $acl.SetOwner($sid)
    foreach ($identity in @($sid, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    # Create with the protected ACL before writing any secret.
    [IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($Path),$acl)
}
function Read-BootstrapPrivateConfig([string]$Path, [string]$Repository) {
    $safe = Assert-BootstrapExternalPath $Path $Repository
    try { $config = Get-Content -LiteralPath $safe -Raw | ConvertFrom-Json } catch { throw 'Cannot parse private bootstrap config. Values are not printed.' }
    if ((@($config.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'childToken,parentToken,public') { throw 'Invalid private bootstrap config shape.' }
    if ((@($config.public.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'deviceId,familyId,roles,versionCode,versionName' -or (@($config.public.roles.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'child,parent') { throw 'Private config public metadata must contain only fixed scopes and hashes.' }
    if ($config.public.familyId -cne 'family-01' -or $config.public.deviceId -cne 'child-01' -or $config.public.versionName -cne '2.3.1' -or $config.public.versionCode -ne 35) { throw 'Invalid bootstrap release scope.' }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($role in @('parent','child')) {
        $token = $config.($role+'Token'); $entry = $config.public.roles.$role
        if ((@($entry.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'expiresAt,sha256') { throw 'Invalid role hash metadata shape.' }
        if ($token -cnotmatch '^[A-Za-z0-9_-]{43}$' -or (Get-BootstrapHash $token) -cne $entry.sha256 -or $entry.expiresAt -le $now -or $entry.expiresAt -gt $now + 604800000) { throw 'Missing, mismatched or expired role bootstrap.' }
        $decoded = [Convert]::FromBase64String($token.Replace('-','+').Replace('_','/')+'=')
        if ($decoded.Length -ne 32 -or [Convert]::ToBase64String($decoded).TrimEnd('=').Replace('+','-').Replace('/','_') -cne $token) { throw 'Invalid canonical 256-bit bootstrap encoding.' }
    }
    if ($config.parentToken -ceq $config.childToken) { throw 'Role capabilities must be distinct.' }
    $config
}
function Assert-BootstrapApkPair([string]$Directory) {
    $manifest = Join-Path $Directory 'BOOTSTRAP-PROVISIONING.json'
    if (-not (Test-Path -LiteralPath $manifest)) { throw 'v231 signing blocked: missing local bootstrap provisioning evidence. CI APKs cannot be signed for zero-setup.' }
    try { $config = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json } catch { throw 'Cannot parse hash-only bootstrap provisioning evidence.' }
    if ((@($config.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'deviceId,familyId,roles,versionCode,versionName' -or (@($config.roles.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'child,parent') { throw 'Bootstrap manifest must contain only public scopes and hashes.' }
    if ($config.familyId -cne 'family-01' -or $config.deviceId -cne 'child-01' -or $config.versionName -cne '2.3.1' -or $config.versionCode -ne 35) { throw 'Invalid bootstrap manifest scope.' }
    if ($config.roles.parent.sha256 -ceq $config.roles.child.sha256) { throw 'Bootstrap role hashes must differ.' }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($role in @('parent','child')) {
        $entry = $config.roles.$role
        if ((@($entry.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'expiresAt,sha256') { throw 'Bootstrap manifest cannot contain plaintext capabilities.' }
        if ($entry.sha256 -cnotmatch '^[A-Za-z0-9_-]{43}$' -or $entry.expiresAt -le $now -or $entry.expiresAt -gt $now + 604800000) { throw 'Invalid or expired bootstrap provisioning evidence.' }
        $app = (Get-Culture).TextInfo.ToTitleCase($role)
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $Directory "Family-$app-v2.3.1-unsigned.apk"))
        $hashes = [Collections.Generic.HashSet[string]]::new()
        try {
            foreach ($dex in @($zip.Entries | Where-Object { $_.FullName -cmatch '^classes[0-9]*\.dex$' })) {
                $stream=$dex.Open(); $memory=[IO.MemoryStream]::new()
                try { $stream.CopyTo($memory); $text=[Text.Encoding]::Latin1.GetString($memory.ToArray()) } finally { $stream.Dispose(); $memory.Dispose() }
                # DEX MUTF-8 ASCII strings have a NUL terminator; capability length is 43.
                foreach ($match in [regex]::Matches($text,'(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?=\x00)')) { [void]$hashes.Add((Get-BootstrapHash $match.Value)) }
            }
        } finally { $zip.Dispose() }
        $other = if ($role -ceq 'parent') { 'child' } else { 'parent' }
        if (-not $hashes.Contains($entry.sha256) -or $hashes.Contains($config.roles.$other.sha256)) { throw 'APK bootstrap missing/mismatched or cross-role capability leaked. Nothing signed.' }
    }
}
