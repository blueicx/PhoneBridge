$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'path_safety.psm1') -Force
$backupScript = Join-Path $PSScriptRoot 'backup_runtime.ps1'
$restoreScript = Join-Path $PSScriptRoot 'restore_runtime.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "PhoneBridgeRuntimeBackupTests-$([guid]::NewGuid().ToString('N'))"
$activityAt = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).ToUnixTimeMilliseconds()
$activityDate = [DateTimeOffset]::FromUnixTimeMilliseconds($activityAt).ToOffset([TimeSpan]::FromHours(8)).ToString('yyyy-MM-dd')
$growthEventId = "reality-lens:v2:$activityDate`:cell:1:2`:object`:backup-fixture"

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
  Write-Utf8File (Join-Path $runtimeDir 'runtime-state.json') '{"state":"before","logs":["log must not be backed up"],"latitude":51.5,"photoData":"raw image bytes","chatHistory":[{"text":"chat must not be backed up"}]}'
  Write-Utf8File (Join-Path $runtimeDir 'workspace-state.json') '{"workspace":"before","logs":["workspace log must not be backed up"]}'
  Write-Utf8File (Join-Path $runtimeDir 'mote-story.json') '{"version":4,"storyProgress":{"exclusive-mote":{"started":true,"stepIndex":2,"startedAt":1000,"stepReceipts":[{"eventId":"story-step:exclusive-mote:chapter-1","stepId":"chapter-1","advancedAt":1200}]}},"logs":["story log must not be backed up"]}'
  $dailyState = [ordered]@{
    date = $activityDate
    clues = [ordered]@{ location = 0; object = 1; light = 0 }
    completed = $false
  }
  $dailyByDate = @{}
  $dailyByDate[$activityDate] = $dailyState
  $growthState = [ordered]@{
    version = 2
    xp = 17
    level = 1
    region = 'cell:1:2'
    daily = $dailyState
    dailyByDate = $dailyByDate
    boosts = @()
    seenEventIds = @($growthEventId)
    processedEvents = @([ordered]@{
      eventId = $growthEventId
      businessStatus = 'accepted'
      reason = 'reward_applied'
      revision = 1
      activityDate = $activityDate
      region = 'cell:1:2'
      clueType = 'object'
      reward = [ordered]@{
        xp = 5
        dailyCompleted = $false
        boost = $null
        encounter = [ordered]@{ id = 'lost-signal'; action = 'skill' }
      }
      createdAt = $activityAt
    })
    growthRecords = @([ordered]@{ eventId = $growthEventId; reward = [ordered]@{ xp = 5; encounter = [ordered]@{ id = 'lost-signal'; action = 'skill' } } })
    revision = 1
    updatedAt = $activityAt
  }
  Write-Utf8File (Join-Path $runtimeDir 'mote-growth.json') (ConvertTo-Json -InputObject $growthState -Depth 12 -Compress)
  Write-Utf8File (Join-Path $runtimeDir 'reality-state.json') '{"version":4,"activeEncounter":{"eventId":"reality:v2:sample","encounterId":"encounter_11","step":0},"seenEventIds":[]}'
  Write-Utf8File (Join-Path $runtimeDir 'access.token') 'must-not-be-backed-up'
  Write-Utf8File (Join-Path $runtimeDir 'crash.log') 'must-not-be-backed-up'
  $nestedBackupRoot = Join-Path $runtimeDir 'nested-backups'
  Assert-Fails { & $backupScript -RuntimeDir $runtimeDir -Destination $nestedBackupRoot } 'backup must reject a destination inside the runtime directory'
  Assert-True (-not (Test-Path -LiteralPath $nestedBackupRoot)) 'rejected nested backup destination must not create a directory inside runtime state'

  $backupOutput = & $backupScript -RuntimeDir $runtimeDir -Destination $backupRoot
  $backupPath = ([string]($backupOutput | Select-Object -Last 1) -replace '^runtime backup created: ', '')
  $manifest = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'backup-manifest.json') | ConvertFrom-Json
  Assert-True ($manifest.formatVersion -eq 3) 'backup manifest must use format version 3'
  $backupNames = @($manifest.files | ForEach-Object { $_.name })
  Assert-True ($backupNames -contains 'mote-story.json') 'backup must include durable Mote story progress'
  Assert-True ($backupNames -contains 'mote-growth.json') 'backup must include Mote growth and encounter receipts'
  Assert-True ($backupNames -contains 'reality-state.json') 'backup must include active Reality encounter state'
  Assert-True (-not (Test-Path -LiteralPath (Join-Path $backupPath 'access.token'))) 'backup must exclude access.token'
  Assert-True (-not (Test-Path -LiteralPath (Join-Path $backupPath 'crash.log'))) 'backup must exclude logs'
  $sanitizedRuntime = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'runtime-state.json') | ConvertFrom-Json
  Assert-True ($null -eq $sanitizedRuntime.state.logs -and $sanitizedRuntime.state.chatHistory[0].text -eq 'chat must not be backed up') 'backup must strip embedded logs while preserving recoverable conversations'
  Assert-True ($null -eq $sanitizedRuntime.state.latitude -and $null -eq $sanitizedRuntime.state.photoData) 'backup must exclude exact location and raw image fields'
  Assert-True (-not ([IO.File]::ReadAllText((Join-Path $backupPath 'runtime-state.json')).Contains('log must not be backed up'))) 'backup must not retain embedded log text'
  $sanitizedWorkspace = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'workspace-state.json') | ConvertFrom-Json
  Assert-True ($sanitizedWorkspace.state.workspace -eq 'before' -and $null -eq $sanitizedWorkspace.state.logs) 'backup must sanitize every allowlisted state snapshot'
  $sanitizedStory = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'mote-story.json') | ConvertFrom-Json
  Assert-True ($sanitizedStory.state.storyProgress.'exclusive-mote'.stepIndex -eq 2 -and $null -eq $sanitizedStory.state.logs) 'backup must preserve chapter progress while removing embedded logs'
  $sanitizedGrowth = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'mote-growth.json') | ConvertFrom-Json
  Assert-True ($sanitizedGrowth.state.xp -eq 17 -and $sanitizedGrowth.state.processedEvents[0].eventId -eq $growthEventId -and $sanitizedGrowth.state.growthRecords[0].reward.encounter.id -eq 'lost-signal') 'backup must preserve Mote growth and encounter receipts'
  $sanitizedReality = Get-Content -Raw -LiteralPath (Join-Path $backupPath 'reality-state.json') | ConvertFrom-Json
  Assert-True ($sanitizedReality.state.activeEncounter.eventId -eq 'reality:v2:sample') 'backup must preserve resumable Reality encounter state'

  $global:PhoneBridgeTestWrongPassphrase = $false
  function global:Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    $value = if ($global:PhoneBridgeTestWrongPassphrase) { 'incorrect test passphrase' } else { 'phonebridge test passphrase 2026' }
    if ($AsSecureString) { return ConvertTo-SecureString $value -AsPlainText -Force }
    return $value
  }
  try {
    $encryptedOutput = & $backupScript -RuntimeDir $runtimeDir -Destination $backupRoot -Encrypt
    $encryptedPath = [string]($encryptedOutput | Select-Object -Last 1) -replace '^encrypted runtime backup created: ', ''
    Assert-True (Test-Path -LiteralPath $encryptedPath -PathType Leaf) 'encrypted backup must create a .pbenc file'
    Assert-True ([IO.Path]::GetExtension($encryptedPath) -eq '.pbenc') 'encrypted backup must use the .pbenc extension'
    Assert-True (-not (Test-Path -LiteralPath ($encryptedPath -replace '\.pbenc$', ''))) 'encrypted backup must remove its plaintext snapshot directory'
    $encryptedBytes = [IO.File]::ReadAllText($encryptedPath)
    Assert-True (-not $encryptedBytes.Contains('chat must not be backed up')) 'encrypted archive envelope must not expose plaintext state'
    $encryptedRestoreTempBefore = @(Get-ChildItem -LiteralPath ([IO.Path]::GetTempPath()) -Directory -Filter 'PhoneBridgeEncryptedRestore-*' | ForEach-Object { $_.FullName })
    & $restoreScript -Source $encryptedPath -RuntimeDir $runtimeDir -VerifyOnly | Out-Null
    $newEncryptedRestoreTemp = @(Get-ChildItem -LiteralPath ([IO.Path]::GetTempPath()) -Directory -Filter 'PhoneBridgeEncryptedRestore-*' | Where-Object { $_.FullName -notin $encryptedRestoreTempBefore })
    Assert-True ($newEncryptedRestoreTemp.Count -eq 0) 'encrypted VerifyOnly must remove its temporary plaintext extraction directory'
    $global:PhoneBridgeTestWrongPassphrase = $true
    Assert-Fails { & $restoreScript -Source $encryptedPath -RuntimeDir $runtimeDir -VerifyOnly } 'encrypted VerifyOnly must reject an incorrect passphrase'
    $global:PhoneBridgeTestWrongPassphrase = $false
  } finally {
    Remove-Item Function:\Read-Host -ErrorAction SilentlyContinue
    Remove-Variable PhoneBridgeTestWrongPassphrase -Scope Global -ErrorAction SilentlyContinue
  }

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
  $leftoverRestoreStages = @(Get-ChildItem -LiteralPath $runtimeDir -Directory -Force | Where-Object { $_.Name -match '^\.phonebridge-restore-[a-f0-9]{32}$' })
  Assert-True ($leftoverRestoreStages.Count -eq 0) 'failed restore must remove its rollback staging directory'
  & $restoreScript -Source $backupPath -RuntimeDir $runtimeDir -ConfirmNodeStopped | Out-Null
  $restoredRuntime = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'runtime-state.json') | ConvertFrom-Json
  Assert-True ($restoredRuntime.schemaVersion -eq 3 -and $restoredRuntime.state.state -eq 'before') 'restore must replace the selected runtime state'
  $restoredStory = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'mote-story.json') | ConvertFrom-Json
  Assert-True ($restoredStory.state.storyProgress.'exclusive-mote'.stepIndex -eq 2) 'restore must retain resumable Mote story chapter progress'
  $restoredGrowth = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'mote-growth.json') | ConvertFrom-Json
  Assert-True ($restoredGrowth.state.processedEvents[0].eventId -eq $growthEventId -and $restoredGrowth.state.growthRecords[0].reward.encounter.id -eq 'lost-signal') 'restore must retain encounter reward receipts'
  $restoredReality = Get-Content -Raw -LiteralPath (Join-Path $runtimeDir 'reality-state.json') | ConvertFrom-Json
  Assert-True ($restoredReality.state.activeEncounter.eventId -eq 'reality:v2:sample') 'restore must retain in-progress Reality encounters'
  $repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
  $smokePath = Join-Path $testRoot 'verify-restored-state.cjs'
  $smokeScript = @'
const assert = require('node:assert/strict');
const path = require('node:path');
const [repositoryRoot, runtimeDir, activityAtValue, growthEventId] = process.argv.slice(2);
const activityAt = Number(activityAtValue);
const { RuntimePersistence } = require(path.join(repositoryRoot, 'server/runtime-persistence'));
const { MoteStoryStore } = require(path.join(repositoryRoot, 'server/mote-story'));
const { MoteGrowthStore } = require(path.join(repositoryRoot, 'server/mote-growth'));
const persistence = new RuntimePersistence({ dir: runtimeDir, now: () => activityAt });
const story = new MoteStoryStore({ persistence, now: () => activityAt });
const storyReplay = story.advance({
  storyId: 'exclusive-mote',
  eventId: 'story-step:exclusive-mote:chapter-1',
  stepId: 'chapter-1',
  activeId: 'sprite',
});
assert.equal(storyReplay.duplicate, true, 'reopened story store must recognize an already-applied step');
assert.equal(story.snapshot().storyProgress['exclusive-mote'].stepIndex, 2, 'story replay must not advance progress twice');
const growth = new MoteGrowthStore({ persistence, now: () => activityAt });
const growthReplay = growth.recordClue({
  eventId: growthEventId,
  clueType: 'object',
  region: 'cell:1:2',
  activityAt,
  offline: false,
});
assert.equal(growthReplay.businessStatus, 'duplicate', 'reopened growth store must recognize an already-applied clue');
assert.equal(growthReplay.reward.xp, 0, 'replayed clue must not award XP again');
assert.equal(growth.snapshot().xp, 17, 'replayed clue must preserve the restored XP balance');
const reality = persistence.load('reality-state', {});
assert.equal(reality.activeEncounter.eventId, 'reality:v2:sample', 'reopened persistence must retain the in-progress encounter');
'@
  Write-Utf8File $smokePath $smokeScript
  & node $smokePath $repositoryRoot $runtimeDir "$activityAt" $growthEventId
  Assert-True ($LASTEXITCODE -eq 0) 'runtime stores must reopen restored snapshots and deduplicate replayed story and growth events'
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
  $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
  if ((Test-PhoneBridgePathWithinDirectory -Path $resolvedTestRoot -Directory ([IO.Path]::GetTempPath())) -and
      (Split-Path -Leaf $resolvedTestRoot).StartsWith('PhoneBridgeRuntimeBackupTests-', [StringComparison]::Ordinal)) {
    Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
  if (Test-Path -LiteralPath $resolvedTestRoot) { throw 'runtime backup test sandbox was not removed' }
}
