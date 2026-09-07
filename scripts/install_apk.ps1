<#
.SYNOPSIS
    Single-session ADB installer for PhoneBridge on Xperia XZ2.
#>

param(
    [int]$Port = 5038,
    [string]$Serial = "QV7017NH1F",
    [string]$ApkPath = "F:\CodexApps\PhoneBridge\android\app\build\outputs\apk\debug\app-debug.apk"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "=== PhoneBridge APK Installer ===" -ForegroundColor Cyan
Write-Host "APK: $ApkPath"
Write-Host "Port: $Port | Target: $Serial"

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK file not found: $ApkPath"
    exit 1
}

# 1. Start server and wait for device
& $adb -P $Port start-server
Start-Sleep -Seconds 1

Write-Host "`nWaiting for device $Serial..." -ForegroundColor Yellow
$deviceState = ""
for ($i = 0; $i -lt 15; $i++) {
    $devices = & $adb -P $Port devices -l
    if ($devices | Where-Object { $_ -match "^$Serial\s+device" }) {
        $deviceState = "device"
        break
    } elseif ($devices | Where-Object { $_ -match "^$Serial\s+offline" }) {
        Write-Host "Device is offline, attempting reconnect..." -ForegroundColor Yellow
        & $adb -P $Port reconnect
    }
    Start-Sleep -Seconds 1
}

if ($deviceState -ne "device") {
    Write-Host "[BLOCKED] Device $Serial is not ready (Output: $($devices -join '; '))" -ForegroundColor Red
    Write-Host "Please check USB cable or unlock phone screen." -ForegroundColor Yellow
    exit 2
}

Write-Host "Device $Serial is ready!" -ForegroundColor Green

# 2. Wake & unlock screen
Write-Host "`nWaking and unlocking screen..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial shell input keyevent KEYCODE_WAKEUP
Start-Sleep -Milliseconds 300
& $adb -P $Port -s $Serial shell input keyevent 82
Start-Sleep -Milliseconds 300
& $adb -P $Port -s $Serial shell input swipe 540 1700 540 500 250
Start-Sleep -Milliseconds 400
& $adb -P $Port -s $Serial shell input text '210009'
Start-Sleep -Milliseconds 200
& $adb -P $Port -s $Serial shell input keyevent 66
Start-Sleep -Seconds 1

# 3. Streamed Install
Write-Host "`nInstalling APK to $Serial (size: $((Get-Item $ApkPath).Length) bytes)..." -ForegroundColor Yellow
$installResult = & $adb -P $Port -s $Serial install -r -d $ApkPath
Write-Host "Install output: $installResult" -ForegroundColor Green

if ($installResult -match "Success") {
    Write-Host "[SUCCESS] APK installed successfully!" -ForegroundColor Green
} else {
    Write-Host "[WARNING] Install output did not explicitly report Success." -ForegroundColor Yellow
}

# 4. Set reverse port and launch app
Write-Host "`nSetting up reverse proxy (9503 -> 9503)..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial reverse tcp:9503 tcp:9503

Write-Host "`nLaunching PhoneBridge MainActivity..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial shell am start -n com.phonebridge/.MainActivity --es server_url 'ws://127.0.0.1:9503'
Start-Sleep -Seconds 3

# 5. Capture verification screenshot
$screenshot = "F:\CodexApps\PhoneBridge\w4_ar_companion_installed.png"
Write-Host "`nCapturing screenshot to $screenshot..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial exec-out screencap -p > $screenshot
if (Test-Path $screenshot) {
    Write-Host "Screenshot saved: $screenshot" -ForegroundColor Green
}

Write-Host "`n=== Installation Finished ===" -ForegroundColor Green
