$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb -P 5039 shell uiautomator dump /sdcard/ptt_test.xml
& $adb -P 5039 pull /sdcard/ptt_test.xml F:\CodexApps\PhoneBridge\ptt_test.xml
[xml]$ui = Get-Content -Raw F:\CodexApps\PhoneBridge\ptt_test.xml
$button = $ui.SelectNodes('//node') | Where-Object { $_.'resource-id' -eq 'com.phonebridge:id/pttButton' }
if (-not $button) { throw 'PTT button not found' }
$numbers = [regex]::Matches($button.bounds, '\d+') | ForEach-Object { [int]$_.Value }
$x = [int](($numbers[0] + $numbers[2]) / 2)
$y = [int](($numbers[1] + $numbers[3]) / 2)

$before = Invoke-RestMethod http://127.0.0.1:9501/status
$beforeFile = Get-Item F:\CodexApps\PhoneBridge\server\frames\pending_audio.pcm -ErrorAction SilentlyContinue
& $adb -P 5039 shell input swipe $x $y $x $y 1200
Start-Sleep -Seconds 3
$after = Invoke-RestMethod http://127.0.0.1:9501/status
$afterFile = Get-Item F:\CodexApps\PhoneBridge\server\frames\pending_audio.pcm -ErrorAction SilentlyContinue

[PSCustomObject]@{
    ButtonCenter = "$x,$y"
    AudioChunksBefore = $before.audioChunks
    AudioChunksAfter = $after.audioChunks
    Recording = $after.recording
    PendingBytesBefore = if ($beforeFile) { $beforeFile.Length } else { 0 }
    PendingBytesAfter = if ($afterFile) { $afterFile.Length } else { 0 }
    PendingChanged = if ($beforeFile -and $afterFile) { $afterFile.LastWriteTime -gt $beforeFile.LastWriteTime } else { $true }
} | Format-List
