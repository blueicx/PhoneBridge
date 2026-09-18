param(
  [string]$Repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $Repository
$tracked = git ls-files
$excluded = @('server/access.token', '*.log', '*.apk', 'server/frames/*', 'server/screenshots/*', 'node_modules/*')
$patterns = @(
  '-----BEGIN (RSA|OPENSSH|EC|PRIVATE) KEY-----',
  '(?i)gh[pousr]_[A-Za-z0-9_]{20,}',
  '(?i)sk-[A-Za-z0-9_-]{20,}',
  '(?i)AIzaSy[A-Za-z0-9_-]{20,}',
  '(?i)bearer\s+[A-Za-z0-9._-]{24,}',
  '(?i)phonebridge_token\s*[=:]\s*[A-Za-z0-9_-]{24,}'
)
$hits = @()
foreach ($file in $tracked) {
  if ($excluded | Where-Object { $file -like $_ }) { continue }
  if (-not (Test-Path -LiteralPath $file)) { continue }
  $text = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
  foreach ($pattern in $patterns) {
    if ($text -match $pattern) { $hits += $file; break }
  }
}
if ($hits.Count -gt 0) { throw "Potential secret material found: $($hits -join ', ')" }
Write-Output 'secret scan passed'
