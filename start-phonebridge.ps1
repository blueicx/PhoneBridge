param(
    [int]$Port = 9501
)

$root = $PSScriptRoot
$serverLog = Join-Path $root "server\server.log"
$serverErr = Join-Path $root "server\server.err.log"
$tunnelLog = Join-Path $root "tunnel.log"
$tunnelErr = Join-Path $root "tunnel.err.log"

if (-not (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath "node" `
        -ArgumentList @("index.js") `
        -WorkingDirectory (Join-Path $root "server") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $serverLog `
        -RedirectStandardError $serverErr
}

if (-not (Get-Process cloudflared -ErrorAction SilentlyContinue)) {
    Remove-Item $tunnelLog, $tunnelErr -ErrorAction SilentlyContinue
    Start-Process -FilePath (Join-Path $root "cloudflared.exe") `
        -ArgumentList @("tunnel", "--url", "http://127.0.0.1:$Port", "--no-autoupdate") `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $tunnelLog `
        -RedirectStandardError $tunnelErr
}

Start-Sleep -Seconds 8
$url = (Get-Content $tunnelLog, $tunnelErr -ErrorAction SilentlyContinue |
    Select-String -Pattern "https://[a-z0-9-]+\.trycloudflare\.com" |
    Select-Object -First 1).Matches[0].Value

[PSCustomObject]@{
    Local = "http://127.0.0.1:$Port"
    PublicWebSocket = if ($url) { $url -replace "^http", "ws" } else { $null }
    Status = "http://127.0.0.1:$Port/status"
} | Format-List
