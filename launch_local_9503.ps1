$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -P 5039 reverse tcp:9503 tcp:9503
& $adb -P 5039 shell am force-stop com.phonebridge
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity --es server_url 'ws://127.0.0.1:9503'
Start-Sleep -Seconds 5
$status = Invoke-RestMethod http://127.0.0.1:9503/api/state
[PSCustomObject]@{
    Clients = $status.stats.clients
    Frames = $status.stats.frames
    AudioChunks = $status.stats.audioChunks
} | Format-List
