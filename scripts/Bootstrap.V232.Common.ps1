#requires -Version 7.2
. (Join-Path $PSScriptRoot 'Bootstrap.Common.ps1')
function Read-BootstrapPrivateConfigV232([string]$Path, [string]$Repository) {
    $safe = Assert-BootstrapExternalPath $Path $Repository
    try { $config = Get-Content -LiteralPath $safe -Raw | ConvertFrom-Json } catch { throw 'Cannot parse private bootstrap config. Values are not printed.' }
    if ((@($config.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'childToken,parentToken,public') { throw 'Invalid private bootstrap config shape.' }
    if ((@($config.public.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'bootstrapMode,deviceId,familyId,roles,versionCode,versionName' -or (@($config.public.roles.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'child,parent') { throw 'Private config public metadata must contain only fixed scopes and hashes.' }
    if ($config.public.familyId -cne 'family-01' -or $config.public.deviceId -cne 'child-01' -or $config.public.versionName -cne '2.3.2' -or $config.public.versionCode -ne 36 -or $config.public.bootstrapMode -cne 'UNTIL_CONSUMED_OR_REVOKED') { throw 'Invalid bootstrap release scope.' }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($role in @('parent','child')) {
        $token = $config.($role+'Token'); $entry = $config.public.roles.$role
        if ((@($entry.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'sha256') { throw 'Invalid role hash metadata shape.' }
        if ($token -cnotmatch '^[A-Za-z0-9_-]{43}$' -or (Get-BootstrapHash $token) -cne $entry.sha256) { throw 'Missing, mismatched or expired role bootstrap.' }
        $decoded = [Convert]::FromBase64String($token.Replace('-','+').Replace('_','/')+'=')
        if ($decoded.Length -ne 32 -or [Convert]::ToBase64String($decoded).TrimEnd('=').Replace('+','-').Replace('/','_') -cne $token) { throw 'Invalid canonical 256-bit bootstrap encoding.' }
    }
    if ($config.parentToken -ceq $config.childToken) { throw 'Role capabilities must be distinct.' }
    $config
}
function Assert-BootstrapApkPairV232([string]$Directory) {
    $manifest = Join-Path $Directory 'BOOTSTRAP-PROVISIONING.json'
    if (-not (Test-Path -LiteralPath $manifest)) { throw 'v232 signing blocked: missing local bootstrap provisioning evidence. CI APKs cannot be signed for zero-setup.' }
    try { $config = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json } catch { throw 'Cannot parse hash-only bootstrap provisioning evidence.' }
    if ((@($config.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'bootstrapMode,deviceId,familyId,roles,versionCode,versionName' -or (@($config.roles.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'child,parent') { throw 'Bootstrap manifest must contain only public scopes and hashes.' }
    if ($config.familyId -cne 'family-01' -or $config.deviceId -cne 'child-01' -or $config.versionName -cne '2.3.2' -or $config.versionCode -ne 36 -or $config.bootstrapMode -cne 'UNTIL_CONSUMED_OR_REVOKED') { throw 'Invalid bootstrap manifest scope.' }
    if ($config.roles.parent.sha256 -ceq $config.roles.child.sha256) { throw 'Bootstrap role hashes must differ.' }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($role in @('parent','child')) {
        $entry = $config.roles.$role
        if ((@($entry.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'sha256') { throw 'Bootstrap manifest cannot contain plaintext capabilities.' }
        if ($entry.sha256 -cnotmatch '^[A-Za-z0-9_-]{43}$') { throw 'Invalid or expired bootstrap provisioning evidence.' }
        $app = (Get-Culture).TextInfo.ToTitleCase($role)
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $Directory "Family-$app-v2.3.2-unsigned.apk"))
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
