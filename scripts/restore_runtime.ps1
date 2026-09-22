param(
  [Parameter(Mandatory=$true)][string]$Source,
  [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\server')
)
$ErrorActionPreference = 'Stop'
$Source = (Resolve-Path -LiteralPath $Source).Path
$RuntimeDir = (Resolve-Path -LiteralPath $RuntimeDir).Path
$allowed = @(
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
foreach ($file in Get-ChildItem -LiteralPath $Source -File) {
  $isAllowed = $allowed -contains $file.Name -or $file.Name -match '^(runtime-state|workspace-state|workspace-timeline|mote-state|mote-relationship|mote-quests|provider-settings)\.json\.bak\.\d+$'
  if (-not $isAllowed) { continue }
  Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $RuntimeDir $file.Name) -Force
}
Write-Output "runtime restore completed from: $Source"
