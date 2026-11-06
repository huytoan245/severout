#requires -Version 7.2
param([string]$AdbPath='C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe')
$ErrorActionPreference='Stop'
function Read-LanEndpoint([string]$Prompt){
 $value=(Read-Host $Prompt).Trim()
 if($value-cnotmatch'^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$'){throw 'Enter the IPv4:port displayed on the phone'}
 $address=[Net.IPAddress]::Parse($matches[1]);$port=[int]$matches[2];$bytes=$address.GetAddressBytes()
 $local=($bytes[0]-eq10)-or($bytes[0]-eq192-and$bytes[1]-eq168)-or($bytes[0]-eq172-and$bytes[1]-ge16-and$bytes[1]-le31)-or($bytes[0]-eq169-and$bytes[1]-eq254)
 if(-not$local-or$port-lt1-or$port-gt65535){throw 'Expected a local Wi-Fi IPv4 address and valid port'}
 return $value
}
if(-not(Test-Path -LiteralPath $AdbPath)){throw 'Android platform-tools adb.exe is missing'}
Write-Host 'Same Wi-Fi required. On Parent: Wireless debugging > Pair device with pairing code. Keep that screen open.'
$pairEndpoint=Read-LanEndpoint 'Enter pairing IP:port from that dialog (not the six-digit code)'
Write-Host 'Enter the pairing code directly at the adb prompt below. Do not send it in chat.'
& $AdbPath pair $pairEndpoint
if($LASTEXITCODE-ne0){throw 'Pairing failed; keep the phone pairing screen open and retry with its current IP/port/code'}
Write-Host 'Return to the main Wireless debugging screen. Its connection port is usually DIFFERENT from the pairing port.'
$connectEndpoint=Read-LanEndpoint 'Enter IP:port from the main Wireless debugging screen'
& $AdbPath connect $connectEndpoint
if($LASTEXITCODE-ne0){throw 'Wireless connection failed; check same Wi-Fi and current connection port'}
$state=& $AdbPath -s $connectEndpoint get-state 2>$null
if($LASTEXITCODE-ne0-or($state-join'').Trim()-cne'device'){throw 'Not connected/authorized yet'}
Write-Output 'PASS: wireless debugging connected. No APK installation, Auth/key read/reset, rebind or production request performed.'
