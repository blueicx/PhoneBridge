$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$url = 'wss://outstanding-runs-gulf-wings.trycloudflare.com'

& $adb -P 5039 shell am force-stop com.phonebridge
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity --es server_url $url
Start-Sleep -Seconds 12

$before = Invoke-RestMethod http://127.0.0.1:9501/status
$after = Invoke-RestMethod http://127.0.0.1:9501/status
[PSCustomObject]@{
    Clients = $after.clients
    Frames = $after.frames
    AudioChunks = $after.audioChunks
} | Format-List
