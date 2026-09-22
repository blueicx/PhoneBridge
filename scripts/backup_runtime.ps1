param(
  [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\server'),
  [string]$Destination = (Join-Path $PSScriptRoot '..\runtime-backups')
)
$ErrorActionPreference = 'Stop'
$RuntimeDir = (Resolve-Path -LiteralPath $RuntimeDir).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$target = Join-Path (Resolve-Path -LiteralPath $Destination).Path "runtime-$stamp"
New-Item -ItemType Directory -Path $target -Force | Out-Null
$names = @(
  'runtime-state.json',
  'workspace-state.json',
  'workspace-timeline.json',
  'mote-state.json',
  'mote-relationship.json',
  'mote-quests.json',
  'mote-growth.json',
  'mote-story.json',
  'reality-state.json',
  'proactive-policy.json',
  'ai-memory.json',
  'provider-settings.json'
)
$copied = @()
foreach ($name in $names) {
  $source = Join-Path $RuntimeDir $name
  if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $target -Force; $copied += Get-Item -LiteralPath (Join-Path $target $name) }
  foreach ($backup in Get-ChildItem -LiteralPath $RuntimeDir -Filter "$name.bak.*" -File -ErrorAction SilentlyContinue) {
    Copy-Item -LiteralPath $backup.FullName -Destination $target -Force
    $copied += Get-Item -LiteralPath (Join-Path $target $backup.Name)
  }
}
$manifest = [ordered]@{
  formatVersion = 2
  createdAt = (Get-Date).ToUniversalTime().ToString('o')
  included = @($copied | ForEach-Object { [ordered]@{ name = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } })
  excluded = @('access.token', 'logs', 'camera frames', 'screenshots', 'APK/AAB', 'dependency caches', 'signing keys', 'precise location')
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $target 'backup-manifest.json') -Encoding UTF8
Write-Output "runtime backup created: $target"
