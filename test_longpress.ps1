$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

function Dump-Ui([string]$Name) {
    & $adb -P 5039 shell uiautomator dump /sdcard/$Name.xml
    & $adb -P 5039 pull /sdcard/$Name.xml "F:\CodexApps\PhoneBridge\$Name.xml"
    [xml]$xml = Get-Content -Raw "F:\CodexApps\PhoneBridge\$Name.xml"
    $xml.SelectNodes('//node') |
        Where-Object { $_.text -ne '' -or $_.'content-desc' -ne '' } |
        ForEach-Object { "$($_.package) | $($_.text) | $($_.'content-desc') | $($_.bounds)" }
}

& $adb -P 5039 shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2
Write-Output '--- before ---'
Dump-Ui longpress_before
& $adb -P 5039 shell dumpsys window | Select-String 'mCurrentFocus'
& $adb -P 5039 shell input swipe 540 900 540 900 1800
Start-Sleep -Seconds 2
Write-Output '--- after ---'
Dump-Ui longpress_after
& $adb -P 5039 shell dumpsys window | Select-String 'mCurrentFocus'
