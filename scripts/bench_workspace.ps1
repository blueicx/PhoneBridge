$ErrorActionPreference = 'Stop'
$result = node server/workspace-performance.bench.js
if ($LASTEXITCODE -ne 0) { throw 'workspace performance budget failed' }
Write-Output $result
