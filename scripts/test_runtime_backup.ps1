$ErrorActionPreference = 'Stop'
$backupScript = Join-Path $PSScriptRoot 'backup_runtime.ps1'
$restoreScript = Join-Path $PSScriptRoot 'restore_runtime.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "PhoneBridgeRuntimeBackupTests-$([guid]::NewGuid().ToString('N'))"

function Write-Utf8File([string]$Path, [string]$Text) {
  [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding $false))
}

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
}

function Assert-Fails([scriptblock]$Action, [string]$Message) {
  $failed = $false
  try { & $Action } catch { $failed = $true }
  Assert-True $failed $Message
}

New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
  $runtimeDir = Join-Path $testRoot 'server'
  $backupRoot = Join-Path $testRoot 'backups'
  New-Item -ItemType Directory -Path $runtimeDir | Out-Null
  Write-Utf8File (Join-Path $runtimeDir 'runtime-state.json') '{"state":"before","logs":["log must not be backed up"],"chatHistory":[{"text":"chat must not be backed up"}]}'
  Write-Utf8File (Join-Path $runtimeDir 'workspace-state.json') '{"workspace":"before","logs":["workspace log must not be backed up"]}'
  Write-Utf8File (Join-Path $runtimeDir 'access.token') 'must-not-be-backed-up'
  Write-Utf8File (Join-Path $runtimeDir 'crash.log') 'must-not-be-backed-up'

  $backupOutput = & $backupScript -RuntimeDir $runtimeDir -Destination $backupRoot
  $backupPath = ([string]($backupOutput | Select-Object -Last 1) -replace '^runtime backup created: ', '')
  $manifest = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'backup-manifest.json') | ConvertFrom-Json
  Assert-True ($manifest.formatVersion -eq 3) 'backup manifest must use format version 3'
  Assert-True (-not (Test-Path -LiteralPath (Join-Path $backupPath 'access.token'))) 'backup must exclude access.token'
  Assert-True (-not (Test-Path -LiteralPath (Join-Path $backupPath 'crash.log'))) 'backup must exclude logs'
  $sanitizedRuntime = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'runtime-state.json') | ConvertFrom-Json
  Assert-True ($null -eq $sanitizedRuntime.state.logs -and $sanitizedRuntime.state.chatHistory[0].text -eq 'chat must not be backed up') 'backup must strip embedded logs while preserving recoverable conversations'
  Assert-True (-not ([IO.File]::ReadAllText((Join-Path $backupPath 'runtime-state.json')).Contains('log must not be backed up'))) 'backup must not retain embedded log text'
  $sanitizedWorkspace = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'workspace-state.json') | ConvertFrom-Json
  Assert-True ($sanitizedWorkspace.state.workspace -eq 'before' -and $null -eq $sanitizedWorkspace.state.logs) 'backup must sanitize every allowlisted state snapshot'

  $beforeVerify = [IO.File]::ReadAllText((Join-Path $runtimeDir 'runtime-state.json'))
  & $restoreScript -Source $backupPath -RuntimeDir $runtimeDir -VerifyOnly | Out-Null
  Assert-True ([IO.File]::ReadAllText((Join-Path $runtimeDir 'runtime-state.json')) -eq $beforeVerify) 'VerifyOnly must not mutate runtime files'

  Write-Utf8File (Join-Path $runtimeDir 'runtime-state.json') '{"state":"after"}'
  Assert-Fails { & $restoreScript -Source $backupPath -RuntimeDir $runtimeDir } 'restore must require explicit confirmation that the node is stopped'
  $lockedTarget = [IO.File]::Open((Join-Path $runtimeDir 'workspace-state.json'), [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
  try {
    Assert-Fails { & $restoreScript -Source $backupPath -RuntimeDir $runtimeDir -ConfirmNodeStopped } 'restore must fail when an existing runtime file cannot be replaced'
  } finally {
    $lockedTarget.Dispose()
  }
  Assert-True ([IO.File]::ReadAllText((Join-Path $runtimeDir 'runtime-state.json')) -eq '{"state":"after"}') 'failed restore must roll back already moved files'
  & $restoreScript -Source $backupPath -RuntimeDir $runtimeDir -ConfirmNodeStopped | Out-Null
  $restoredRuntime = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'runtime-state.json') | ConvertFrom-Json
  Assert-True ($restoredRuntime.schemaVersion -eq 3 -and $restoredRuntime.state.state -eq 'before') 'restore must replace the selected runtime state'
  Assert-True ([IO.File]::ReadAllText((Join-Path $runtimeDir 'access.token')) -eq 'must-not-be-backed-up') 'restore must not change the access token'

  $corruptPath = Join-Path $testRoot 'corrupt-backup'
  Copy-Item -LiteralPath $backupPath -Destination $corruptPath -Recurse
  Write-Utf8File (Join-Path $corruptPath 'runtime-state.json') '{"tampered":true}'
  Assert-Fails { & $restoreScript -Source $corruptPath -RuntimeDir $runtimeDir -VerifyOnly } 'VerifyOnly must reject a modified payload'

  $missingPath = Join-Path $testRoot 'missing-backup'
  Copy-Item -LiteralPath $backupPath -Destination $missingPath -Recurse
  Remove-Item -LiteralPath (Join-Path $missingPath 'workspace-state.json') -Force
  Assert-Fails { & $restoreScript -Source $missingPath -RuntimeDir $runtimeDir -VerifyOnly } 'VerifyOnly must reject missing manifest entries'

  $traversalPath = Join-Path $testRoot 'traversal-backup'
  Copy-Item -LiteralPath $backupPath -Destination $traversalPath -Recurse
  $traversalManifestPath = Join-Path $traversalPath 'backup-manifest.json'
  $traversalManifest = Get-Content -Raw -LiteralPath $traversalManifestPath | ConvertFrom-Json
  $traversalManifest.files[0].name = '..\access.token'
  Write-Utf8File $traversalManifestPath ($traversalManifest | ConvertTo-Json -Depth 8)
  Assert-Fails { & $restoreScript -Source $traversalPath -RuntimeDir $runtimeDir -VerifyOnly } 'VerifyOnly must reject path traversal entries'

  $emptyPath = Join-Path $testRoot 'empty-backup'
  New-Item -ItemType Directory -Path $emptyPath | Out-Null
  Write-Utf8File (Join-Path $emptyPath 'backup-manifest.json') '{"formatVersion":3,"files":[]}'
  Assert-Fails { & $restoreScript -Source $emptyPath -RuntimeDir $runtimeDir -VerifyOnly } 'VerifyOnly must reject an empty backup that could erase all runtime state'

  $legacyPath = Join-Path $testRoot 'legacy-v2'
  New-Item -ItemType Directory -Path $legacyPath | Out-Null
  Write-Utf8File (Join-Path $legacyPath 'runtime-state.json') '{"frameCount":3,"logs":["legacy log"]}'
  $legacyFile = Get-Item -LiteralPath (Join-Path $legacyPath 'runtime-state.json')
  $legacyManifest = [ordered]@{
    formatVersion = 2
    createdAt = [DateTime]::UtcNow.ToString('o')
    included = @([ordered]@{ name = $legacyFile.Name; bytes = $legacyFile.Length; sha256 = (Get-FileHash -LiteralPath $legacyFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() })
    excluded = @('access.token')
  }
  Write-Utf8File (Join-Path $legacyPath 'backup-manifest.json') ($legacyManifest | ConvertTo-Json -Depth 8)
  & $restoreScript -Source $legacyPath -RuntimeDir $runtimeDir -VerifyOnly | Out-Null
  & $restoreScript -Source $legacyPath -RuntimeDir $runtimeDir -ConfirmNodeStopped | Out-Null
  $legacyRestored = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'runtime-state.json') | ConvertFrom-Json
  Assert-True ($legacyRestored.schemaVersion -eq 3 -and $legacyRestored.state.frameCount -eq 3 -and $null -eq $legacyRestored.state.logs) 'legacy restore must migrate and sanitize state'

  Write-Output 'runtime backup/restore tests passed'
} finally {
  $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
  $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
  if ($resolvedTestRoot.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
      (Split-Path -Leaf $resolvedTestRoot).StartsWith('PhoneBridgeRuntimeBackupTests-', [StringComparison]::Ordinal)) {
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
