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
$names = @('runtime-state.json','workspace-state.json','workspace-timeline.json','mote-state.json','mote-relationship.json','mote-quests.json','provider-settings.json')
foreach ($name in $names) {
  $source = Join-Path $RuntimeDir $name
  if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $target -Force }
  foreach ($backup in Get-ChildItem -LiteralPath $RuntimeDir -Filter "$name.bak.*" -File -ErrorAction SilentlyContinue) { Copy-Item -LiteralPath $backup.FullName -Destination $target -Force }
}
Write-Output "runtime backup created: $target"
