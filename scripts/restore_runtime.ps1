param(
  [Parameter(Mandatory=$true)][string]$Source,
  [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\server'),
  [switch]$VerifyOnly,
  [switch]$ConfirmNodeStopped
)
$ErrorActionPreference = 'Stop'

$stateNames = @(
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
$escapedNames = ($stateNames | ForEach-Object { [regex]::Escape($_) }) -join '|'
$allowedPattern = "^(?:$escapedNames)(?:\.bak\.[1-9][0-9]*)?$"
$Source = (Resolve-Path -LiteralPath $Source).Path
$RuntimeDir = (Resolve-Path -LiteralPath $RuntimeDir).Path
if (-not (Test-Path -LiteralPath $Source -PathType Container)) { throw 'backup source must be a directory' }

$manifestPath = Join-Path $Source 'backup-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'backup manifest is missing' }
$manifestItem = Get-Item -LiteralPath $manifestPath -Force
if (($manifestItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'backup manifest cannot be a symlink' }
try { $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json } catch { throw 'backup manifest is invalid JSON' }
$formatVersion = 0
if (-not [int]::TryParse([string]$manifest.formatVersion, [ref]$formatVersion) -or $formatVersion -notin @(2, 3)) {
  throw 'backup manifest format is unsupported'
}
$entries = if ($formatVersion -eq 3) { @($manifest.files) } else { @($manifest.included) }
if ($entries.Count -eq 0) { throw 'backup contains no runtime state files; refusing an empty restore' }
$entryNames = @{}
foreach ($entry in $entries) {
  $name = [string]$entry.name
  if ([string]::IsNullOrWhiteSpace($name) -or [IO.Path]::GetFileName($name) -ne $name -or $name -match '[\\/:]' -or $name -notmatch $allowedPattern) {
    throw 'backup manifest contains a path outside the runtime state allowlist'
  }
  if ($entryNames.ContainsKey($name)) { throw 'backup manifest contains duplicate files' }
  $expectedBytes = 0L
  if (-not [long]::TryParse([string]$entry.bytes, [ref]$expectedBytes) -or $expectedBytes -lt 0) { throw "backup size is invalid: $name" }
  $expectedHash = [string]$entry.sha256
  if ($expectedHash -notmatch '^[a-fA-F0-9]{64}$') { throw "backup SHA-256 is invalid: $name" }
  $filePath = Join-Path $Source $name
  if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) { throw "backup file is missing: $name" }
  $file = Get-Item -LiteralPath $filePath -Force
  if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "backup file cannot be a symlink: $name" }
  if ([long]$file.Length -ne $expectedBytes) { throw "backup size mismatch: $name" }
  $actualHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
  if (-not $actualHash.Equals($expectedHash, [StringComparison]::OrdinalIgnoreCase)) { throw "backup SHA-256 mismatch: $name" }
  $entryNames[$name] = [ordered]@{ name = $name; bytes = $expectedBytes; sha256 = $expectedHash.ToLowerInvariant(); source = $file.FullName }
}

foreach ($item in Get-ChildItem -LiteralPath $Source -Force) {
  if ($item.PSIsContainer) { throw 'backup source cannot contain subdirectories' }
  if ($item.Name -eq 'backup-manifest.json') { continue }
  if (-not $entryNames.ContainsKey($item.Name)) { throw "backup contains an unlisted or disallowed file: $($item.Name)" }
}

if ($VerifyOnly) {
  Write-Output "runtime backup verification passed: $($entryNames.Count) files (format v$formatVersion)"
  return
}
if (-not $ConfirmNodeStopped) { throw 'stop the PhoneBridge node and pass -ConfirmNodeStopped before restoring runtime state' }

$restoreId = [guid]::NewGuid().ToString('N')
$stagingRoot = Join-Path $RuntimeDir ".phonebridge-restore-$restoreId"
$incomingDir = Join-Path $stagingRoot 'incoming'
$rollbackDir = Join-Path $stagingRoot 'rollback'
$preserveStaging = $false
$installedNames = New-Object System.Collections.Generic.List[string]
New-Item -ItemType Directory -Path $incomingDir -Force | Out-Null
New-Item -ItemType Directory -Path $rollbackDir -Force | Out-Null

try {
  foreach ($entry in $entryNames.Values) {
    $stagedFile = Join-Path $incomingDir $entry.name
    Copy-Item -LiteralPath $entry.source -Destination $stagedFile
    $staged = Get-Item -LiteralPath $stagedFile
    $stagedHash = (Get-FileHash -LiteralPath $staged.FullName -Algorithm SHA256).Hash
    if ([long]$staged.Length -ne $entry.bytes -or -not $stagedHash.Equals($entry.sha256, [StringComparison]::OrdinalIgnoreCase)) {
      throw "staged backup changed during restore: $($entry.name)"
    }
    $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
    if (-not $nodeCommand) { throw 'Node.js is required to sanitize runtime state during restore' }
    $sanitizer = Join-Path $PSScriptRoot '..\server\runtime-backup.js'
    & $nodeCommand.Source $sanitizer $stagedFile $stagedFile *> $null
    if ($LASTEXITCODE -ne 0) { throw "runtime state validation failed during restore: $($entry.name)" }
  }

  $restoreFiles = @(Get-ChildItem -LiteralPath $RuntimeDir -File -Force | Where-Object { $_.Name -match $allowedPattern } | Sort-Object Name)
  foreach ($existing in $restoreFiles) {
    if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "runtime state symlinks cannot be replaced: $($existing.Name)" }
  }
  foreach ($entry in $entryNames.Values) {
    $targetPath = Join-Path $RuntimeDir $entry.name
    if ((Test-Path -LiteralPath $targetPath) -and (Test-Path -LiteralPath $targetPath -PathType Container)) {
      throw "runtime target is not a file: $($entry.name)"
    }
  }

  foreach ($existing in $restoreFiles) {
    Move-Item -LiteralPath $existing.FullName -Destination (Join-Path $rollbackDir $existing.Name)
  }
  foreach ($entry in $entryNames.Values) {
    Move-Item -LiteralPath (Join-Path $incomingDir $entry.name) -Destination (Join-Path $RuntimeDir $entry.name)
    $installedNames.Add($entry.name)
  }
  Remove-Item -LiteralPath $stagingRoot -Recurse -Force
  $stagingRoot = $null
  Write-Output "runtime restore completed from: $Source"
} catch {
  $restoreError = $_
  $rollbackError = $null
  try {
    foreach ($name in $installedNames) {
      $installedPath = Join-Path $RuntimeDir $name
      if (Test-Path -LiteralPath $installedPath -PathType Leaf) { Remove-Item -LiteralPath $installedPath -Force }
    }
    foreach ($oldFile in Get-ChildItem -LiteralPath $rollbackDir -File -Force | Sort-Object Name) {
      $targetPath = Join-Path $RuntimeDir $oldFile.Name
      if (Test-Path -LiteralPath $targetPath -PathType Leaf) { Remove-Item -LiteralPath $targetPath -Force }
      Move-Item -LiteralPath $oldFile.FullName -Destination $targetPath
    }
  } catch {
    $rollbackError = $_
    $preserveStaging = $true
  }
  if ($rollbackError) {
    throw "restore failed and automatic rollback was incomplete; recovery snapshot preserved at '$stagingRoot'"
  }
  throw "restore failed; original runtime snapshot was rolled back: $($restoreError.Exception.Message)"
} finally {
  if ($stagingRoot -and -not $preserveStaging -and (Test-Path -LiteralPath $stagingRoot -PathType Container)) {
    $resolvedStaging = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $stagingRoot).Path)
    $resolvedRuntimePrefix = [IO.Path]::GetFullPath($RuntimeDir).TrimEnd('\') + '\'
    if ($resolvedStaging.StartsWith($resolvedRuntimePrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedStaging) -match '^\.phonebridge-restore-[a-f0-9]{32}$') {
      Remove-Item -LiteralPath $resolvedStaging -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}
