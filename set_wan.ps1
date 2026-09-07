$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$url = 'wss://outstanding-runs-gulf-wings.trycloudflare.com'
& $adb -P 5039 shell am start -n com.phonebridge/.MainActivity
Start-Sleep -Seconds 2
& $adb -P 5039 shell input tap 540 271
Start-Sleep -Milliseconds 400
& $adb -P 5039 shell input keyevent KEYCODE_MOVE_END
1..60 | ForEach-Object { & $adb -P 5039 shell input keyevent DEL }
& $adb -P 5039 shell input text $url
Start-Sleep -Milliseconds 300
& $adb -P 5039 shell input keyevent BACK
Start-Sleep -Milliseconds 500
$before = Invoke-RestMethod http://127.0.0.1:9501/status
& $adb -P 5039 shell input tap 285 424
Start-Sleep -Seconds 10
$after = Invoke-RestMethod http://127.0.0.1:9501/status
[PSCustomObject]@{BeforeClients=$before.clients;BeforeFrames=$before.frames;AfterClients=$after.clients;AfterFrames=$after.frames} | Format-List
& $adb -P 5039 shell uiautomator dump /sdcard/wan.xml
& $adb -P 5039 pull /sdcard/wan.xml F:\CodexApps\PhoneBridge\wan.xml
Select-String -Path F:\CodexApps\PhoneBridge\wan.xml -Pattern 'trycloudflare|failed|failure'
