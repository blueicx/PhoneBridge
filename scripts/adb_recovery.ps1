<#
.SYNOPSIS
    PhoneBridge ADB Transport Recovery and Diagnostics Script.
.DESCRIPTION
    Diagnostics and recovery helper for physical Xperia XZ2 (H8296 / QV7017NH1F).
    Checks Windows USB PnP status, probes ADB ports (5038, 5039, 5037),
    wakes & unlocks the screen, configures reverse port forwarding, launches Mote,
    and switches to Wireless ADB (Wi-Fi) to prevent physical cable dropouts.
#>

param(
    [int]$Port = 5038,
    [string]$Serial = "QV7017NH1F",
    [switch]$ResetServer,
    [switch]$EnableWifiAdb,
    # Leave empty by default; never commit or pass a device PIN in source control.
    [string]$Pin = ""
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "ADB not found at: $adb"
    exit 1
}

Write-Host "=== PhoneBridge ADB Transport Diagnostics ===" -ForegroundColor Cyan
Write-Host "Target ADB Port: $Port | Target Serial: $Serial"

# 1. Check Windows USB Enumeration
Write-Host "`n[Step 1] Checking Windows USB Device..." -ForegroundColor Yellow
$usbDevice = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { 
    $_.InstanceId -like "*VID_0FCE&PID_0DDE*" -or $_.InstanceId -like "*$Serial*" 
}

if ($usbDevice) {
    Write-Host "  -> Windows USB detected: $($usbDevice.FriendlyName) (Status: $($usbDevice.Status))" -ForegroundColor Green
} else {
    Write-Host "  -> [WARNING] Sony Xperia XZ2 USB device not detected in Windows PnP tree." -ForegroundColor Red
    Write-Host "     Please check cable, unlock phone screen, or toggle USB Preferences to File Transfer / MTP." -ForegroundColor Yellow
}

# 2. Reset server if requested
if ($ResetServer) {
    Write-Host "`n[Step 2] Resetting ADB Daemon on port $Port..." -ForegroundColor Yellow
    & $adb -P $Port kill-server | Out-Null
    Start-Sleep -Seconds 1
}

# 3. Check ADB Device List
Write-Host "`n[Step 3] Querying ADB devices on port $Port..." -ForegroundColor Yellow
$deviceOutput = & $adb -P $Port devices -l
Write-Host ($deviceOutput -join "`n")

$isAttached = $deviceOutput | Where-Object { $_ -match "^$Serial\s+device" }
if (-not $isAttached) {
    # Check alternate ports 5039 and 5037
    Write-Host "`n[Fallback Check] Probing port 5039 and 5037..." -ForegroundColor Yellow
    foreach ($altPort in @(5039, 5037)) {
        $altOutput = & $adb -P $altPort devices -l 2>$null
        if ($altOutput | Where-Object { $_ -match "^$Serial\s+device" }) {
            Write-Host "  -> Device found on port $altPort! Suggest running: .\adb_recovery.ps1 -Port $altPort" -ForegroundColor Green
            $Port = $altPort
            $isAttached = $true
            break
        }
    }
}

if (-not $isAttached) {
    Write-Host "`n[BLOCKED] Device $Serial is not recognized as an authorized ADB device." -ForegroundColor Red
    Write-Host "Troubleshooting Runbook:" -ForegroundColor Cyan
    Write-Host "  1. Re-plug the USB-C cable firmly into the phone."
    Write-Host "  2. Unlock the phone screen and check for 'Allow USB debugging?' prompt."
    Write-Host "  3. In Developer Options, verify 'USB debugging' is ON."
    Write-Host "  4. Run: .\scripts\adb_recovery.ps1 -ResetServer"
    exit 2
}

Write-Host "`n[SUCCESS] ADB device $Serial connected and authorized!" -ForegroundColor Green

# 4. Wake and Unlock
Write-Host "`n[Step 4] Waking and Unlocking Phone Screen..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial shell input keyevent KEYCODE_WAKEUP
Start-Sleep -Milliseconds 400
& $adb -P $Port -s $Serial shell input keyevent 82
Start-Sleep -Milliseconds 400
& $adb -P $Port -s $Serial shell input swipe 540 1700 540 500 250
Start-Sleep -Milliseconds 500
if ($Pin) {
    & $adb -P $Port -s $Serial shell input text $Pin
    Start-Sleep -Milliseconds 250
    & $adb -P $Port -s $Serial shell input keyevent 66
    Start-Sleep -Seconds 1
}

# 5. Setup Reverse Port Forwarding
Write-Host "`n[Step 5] Setting up reverse port forward (tcp:9503 -> tcp:9503)..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial reverse tcp:9503 tcp:9503
$reverseList = & $adb -P $Port -s $Serial reverse --list
Write-Host "  Active reverse mappings: $reverseList" -ForegroundColor Green

# 6. Launch MainActivity
Write-Host "`n[Step 6] Launching PhoneBridge MainActivity..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial shell am start -n com.phonebridge/.MainActivity --es server_url 'ws://127.0.0.1:9503'
Start-Sleep -Seconds 2

# 7. Optional: Switch to Wireless ADB (Wi-Fi)
if ($EnableWifiAdb) {
    Write-Host "`n[Step 7] Probing Wi-Fi IP for Wireless Debugging..." -ForegroundColor Yellow
    $ipInfo = & $adb -P $Port -s $Serial shell ip -f inet addr show wlan0
    $ipMatch = [regex]::Match($ipInfo, 'inet\s+([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)')
    if ($ipMatch.Success) {
        $wifiIp = $ipMatch.Groups[1].Value
        Write-Host "  Device WLAN IP detected: $wifiIp" -ForegroundColor Green
        Write-Host "  Enabling TCP/IP on port 5555..."
        & $adb -P $Port -s $Serial tcpip 5555
        Start-Sleep -Seconds 2
        Write-Host "  Connecting to $wifiIp:5555..."
        & $adb -P $Port connect "$wifiIp:5555"
        Write-Host "  [TIP] Wireless ADB enabled! You can now disconnect the USB cable if desired." -ForegroundColor Cyan
    } else {
        Write-Host "  Could not find WLAN IP address on wlan0. Wi-Fi may be disconnected." -ForegroundColor Yellow
    }
}

Write-Host "`n=== Recovery and Setup Complete! ===" -ForegroundColor Green
