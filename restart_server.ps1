$connection = Get-NetTCPConnection -LocalPort 9501 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($connection) {
    Stop-Process -Id $connection.OwningProcess -Force
}

Start-Sleep -Seconds 1
$root = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $PSScriptRoot 'server'
Start-Process -FilePath 'node' `
    -ArgumentList @('index.js') `
    -WorkingDirectory $serverRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $serverRoot 'server.log') `
    -RedirectStandardError (Join-Path $serverRoot 'server.err.log')

Start-Sleep -Seconds 2
Invoke-RestMethod http://127.0.0.1:9501/api/state | ConvertTo-Json -Depth 3 -Compress
