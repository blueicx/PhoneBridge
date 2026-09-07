pnputil /restart-device "USB\VID_0FCE&PID_51FA\QV7017NH1F"
Start-Sleep -Seconds 5
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 kill-server
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 start-server
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 devices
