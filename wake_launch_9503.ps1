$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -P 5039 shell input keyevent KEYCODE_WAKEUP
Start-Sleep -Milliseconds 400
& $adb -P 5039 shell input keyevent 82
Start-Sleep -Milliseconds 400
& $adb -P 5039 shell input swipe 540 1700 540 500 250
Start-Sleep -Milliseconds 500
& $adb -P 5039 shell input text '210009'
Start-Sleep -Milliseconds 250
& $adb -P 5039 shell input keyevent 66
Start-Sleep -Seconds 1
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity --es server_url 'ws://127.0.0.1:9503'
Start-Sleep -Seconds 4
& $adb -P 5039 shell dumpsys window | Select-String 'mCurrentFocus'
$status = Invoke-RestMethod http://127.0.0.1:9503/api/state
"clients=$($status.stats.clients) frames=$($status.stats.frames)"
