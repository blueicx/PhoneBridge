$log = "F:\CodexApps\PhoneBridge\usb_restart.log"
"start $(Get-Date -Format o)" | Out-File $log -Encoding utf8
try {
    pnputil /restart-device "USB\VID_0FCE&PID_51FA\QV7017NH1F" 2>&1 | Out-File $log -Append -Encoding utf8
    Start-Sleep -Seconds 8
    & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 kill-server 2>&1 | Out-File $log -Append -Encoding utf8
    & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 start-server 2>&1 | Out-File $log -Append -Encoding utf8
    & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -P 5039 devices -l 2>&1 | Out-File $log -Append -Encoding utf8
} catch {
    $_ | Out-File $log -Append -Encoding utf8
}
"done $(Get-Date -Format o)" | Out-File $log -Append -Encoding utf8
