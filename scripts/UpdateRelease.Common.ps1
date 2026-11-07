#requires -Version 7.2
function Assert-UpdateInPlaceApkPair([string]$Directory,[string]$Aapt2,[ValidateSet('2.3.2','2.3.3')][string]$Version='2.3.2') {
    $code=if($Version -ceq '2.3.3'){37}else{36}
    $gate=Get-Content -LiteralPath (Join-Path $Directory 'AUTOMATED-GATE.json') -Raw | ConvertFrom-Json
    $marker=Get-Content -LiteralPath (Join-Path $Directory 'UPDATE-IN-PLACE.json') -Raw | ConvertFrom-Json
    if($gate.ReleaseMode -cne 'UPDATE_IN_PLACE' -or $marker.mode -cne 'UPDATE_IN_PLACE' -or $marker.versionName -cne $Version -or $marker.versionCode -ne $code -or $gate.VersionName -cne $Version -or $gate.VersionCode -ne $code -or $marker.bootstrapIncluded -ne $false){throw 'Update signing requires the tested UPDATE_IN_PLACE pair; fresh/recovery signing is separate'}
    foreach($app in @('Parent','Child')) {
        $file=Join-Path $Directory "Family-$app-v$Version-unsigned.apk"
        if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $marker.apks.$app){throw 'UPDATE_IN_PLACE candidate hash mismatch'}
        $native=Get-ReleaseNativeApkInput $file
        try{$xml=Invoke-ReleaseTool $Aapt2 @('dump','xmltree','--file','AndroidManifest.xml',$native.Path)}finally{Remove-ReleaseNativeApkInput $native}
        if($xml.ExitCode -ne 0 -or $xml.Output -cnotmatch 'family\.update_in_place[\s\S]{0,240}android:value[^\r\n]*(?:0xffffffff|true)'){throw 'APK manifest does not enforce existing enrollment only'}
    }
}
