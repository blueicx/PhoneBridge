$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$url = 'wss://outstanding-runs-gulf-wings.trycloudflare.com'

& $adb -P 5039 shell am force-stop com.phonebridge
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity --es server_url $url
Start-Sleep -Seconds 12

Get-Process node, cloudflared -ErrorAction SilentlyContinue | Select-Object Id, ProcessName
$status = Invoke-RestMethod http://127.0.0.1:9501/api/state
[PSCustomObject]@{
    Clients = $status.clients
    Frames = $status.stats.frames
    AudioChunks = $status.stats.audioChunks
    HasLiveAudio = $status.hasLiveAudio
} | Format-List
