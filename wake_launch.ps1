$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$url = 'wss://outstanding-runs-gulf-wings.trycloudflare.com'

& $adb -P 5039 shell input keyevent KEYCODE_WAKEUP
Start-Sleep -Milliseconds 500
& $adb -P 5039 shell input keyevent 82
Start-Sleep -Milliseconds 500
& $adb -P 5039 shell input swipe 540 1700 540 500 250
Start-Sleep -Milliseconds 600
& $adb -P 5039 shell input text '210009'
Start-Sleep -Milliseconds 300
& $adb -P 5039 shell input keyevent 66
Start-Sleep -Seconds 2
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity --es server_url $url
Start-Sleep -Seconds 6

& $adb -P 5039 shell dumpsys window | Select-String 'mCurrentFocus'
$status = Invoke-RestMethod http://127.0.0.1:9501/status
[PSCustomObject]@{
    Clients = $status.clients
    Frames = $status.frames
    AudioChunks = $status.audioChunks
} | Format-List
