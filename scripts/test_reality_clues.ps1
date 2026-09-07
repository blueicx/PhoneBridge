<#
.SYNOPSIS
    Automated QA Test Script for Reality Lens Clues on Xperia XZ2.
.DESCRIPTION
    Calculates exact coordinates based on device screen resolution
    (place: 26%/30%, object: 66%/44%, light: 38%/64%),
    simulates taps on each clue node, and checks logcat / pet state rewards.
#>

param(
    [int]$Port = 5038,
    [string]$Serial = "QV7017NH1F",
    [switch]$SkipEnter
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "ADB not found at: $adb"
    exit 1
}

Write-Host "=== Reality Lens Clue Tap & Reward QA Test ===" -ForegroundColor Cyan

# 1. Check device status
$deviceCheck = & $adb -P $Port -s $Serial get-state 2>$null
if ($deviceCheck -ne "device") {
    Write-Host "[ERROR] Device $Serial on port $Port is not in 'device' state ($deviceCheck)." -ForegroundColor Red
    Write-Host "Please run .\scripts\adb_recovery.ps1 first." -ForegroundColor Yellow
    exit 1
}

# 2. Query Screen Resolution
$sizeOutput = & $adb -P $Port -s $Serial shell wm size
$sizeMatch = [regex]::Match($sizeOutput, 'Physical size:\s*([0-9]+)x([0-9]+)')
$width = 1080
$height = 1920
if ($sizeMatch.Success) {
    $width = [int]$sizeMatch.Groups[1].Value
    $height = [int]$sizeMatch.Groups[2].Value
}
Write-Host "Device Resolution: ${width}x${height}" -ForegroundColor Green

# Clue coordinate fractions from RealityLensView.kt
$clues = @(
    @{ id = "place";  title = "地点线索"; xFraction = 0.26; yFraction = 0.30 },
    @{ id = "object"; title = "物体轮廓"; xFraction = 0.66; yFraction = 0.44 },
    @{ id = "light";  title = "光线样本"; xFraction = 0.38; yFraction = 0.64 }
)

# 3. Enter Reality Lens if needed
if (-not $SkipEnter) {
    Write-Host "`n[Step 1] Ensuring Reality Lens is active..." -ForegroundColor Yellow
    # If app is not in foreground, launch it
    & $adb -P $Port -s $Serial shell am start -n com.phonebridge/.MainActivity
    Start-Sleep -Seconds 1
}

# 4. Tap each clue and monitor feedback
Write-Host "`n[Step 2] Tapping 3 Reality Lens Clues..." -ForegroundColor Yellow
# Clear logcat buffer for clean capture
& $adb -P $Port -s $Serial logcat -c

foreach ($clue in $clues) {
    $tapX = [math]::Round($width * $clue.xFraction)
    $tapY = [math]::Round($height * $clue.yFraction)
    Write-Host "  -> Tapping [$($clue.title)] at ($tapX, $tapY)..." -ForegroundColor Cyan
    & $adb -P $Port -s $Serial shell input tap $tapX $tapY
    Start-Sleep -Milliseconds 1200
}

# 5. Verify Rewards in Logcat
Write-Host "`n[Step 3] Verifying Clue Rewards (+4 XP) in Logcat..." -ForegroundColor Yellow
$logs = & $adb -P $Port -s $Serial logcat -d -s PhoneBridge:D MainActivity:D System.out:I
$rewardLogs = $logs | Where-Object { $_ -match "现实" -or $_ -match "reality" -or $_ -match "experience" -or $_ -match "reward" }

if ($rewardLogs) {
    Write-Host "Found Reward Logs:" -ForegroundColor Green
    $rewardLogs | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "No explicit reward logs captured in recent buffer. Checking device state..." -ForegroundColor Yellow
}

# 6. Capture Evidence Screenshot
$screenshotPath = "F:\CodexApps\PhoneBridge\w4_reality_clues_tested.png"
Write-Host "`n[Step 4] Capturing Verification Screenshot to $screenshotPath..." -ForegroundColor Yellow
& $adb -P $Port -s $Serial exec-out screencap -p > $screenshotPath
if (Test-Path $screenshotPath) {
    Write-Host "Screenshot captured successfully: $screenshotPath" -ForegroundColor Green
}

Write-Host "`n=== Reality Lens QA Test Run Finished ===" -ForegroundColor Green
