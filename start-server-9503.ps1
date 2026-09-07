$existing = Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
    Where-Object { $_.CommandLine -like '*run-server-9503.bat*' }
if (-not $existing) {
    Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/c',"$PSScriptRoot\run-server-9503.bat") `
        -WindowStyle Hidden
}

Start-Sleep -Seconds 3
$state = Invoke-RestMethod http://127.0.0.1:9503/api/state
[PSCustomObject]@{
    Port = 9503
    Clients = $state.stats.clients
    Frames = $state.stats.frames
    UptimeMinutes = $state.stats.uptimeMinutes
} | Format-List
