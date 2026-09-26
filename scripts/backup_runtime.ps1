param(
  [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\server'),
  [string]$Destination = (Join-Path $PSScriptRoot '..\runtime-backups'),
  [string]$FlushEndpoint = '',
  [string]$AccessTokenPath = ''
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
$RuntimeDir = (Resolve-Path -LiteralPath $RuntimeDir).Path
$destinationInput = [IO.Path]::GetFullPath($Destination)
if (-not (Test-Path -LiteralPath $destinationInput -PathType Container)) {
  New-Item -ItemType Directory -Path $destinationInput -Force | Out-Null
}
$destinationRoot = (Resolve-Path -LiteralPath $destinationInput).Path
$runtimePrefix = [IO.Path]::GetFullPath($RuntimeDir).TrimEnd('\') + '\'
$destinationPrefix = [IO.Path]::GetFullPath($destinationRoot).TrimEnd('\') + '\'
if ([IO.Path]::GetFullPath($destinationRoot).Equals([IO.Path]::GetFullPath($RuntimeDir), [StringComparison]::OrdinalIgnoreCase) -or
    $destinationPrefix.StartsWith($runtimePrefix, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'backup destination must be outside the runtime directory'
}

if (-not [string]::IsNullOrWhiteSpace($FlushEndpoint)) {
  $flushUri = $null
  if (-not [uri]::TryCreate($FlushEndpoint, [UriKind]::Absolute, [ref]$flushUri)) { throw 'runtime flush endpoint is invalid' }
  $flushHost = $flushUri.Host.Trim('[', ']').ToLowerInvariant()
  $flushIsLoopback = $flushHost -eq 'localhost' -or $flushHost -eq '::1' -or $flushHost -match '^127\.'
  if ($flushUri.Scheme -ne 'https' -and -not ($flushUri.Scheme -eq 'http' -and $flushIsLoopback)) {
    throw 'runtime flush must use HTTPS or loopback HTTP'
  }
  if ([string]::IsNullOrWhiteSpace($AccessTokenPath)) { $AccessTokenPath = Join-Path $RuntimeDir 'access.token' }
  if (-not (Test-Path -LiteralPath $AccessTokenPath -PathType Leaf)) { throw 'runtime flush access token file is missing' }
  $flushToken = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $AccessTokenPath).Path).Trim()
  if ($flushToken.Length -lt 24) { throw 'runtime flush access token is invalid' }
  try {
    $flushResult = Invoke-RestMethod -Uri $flushUri -Method Post -Headers @{ 'x-phonebridge-token' = $flushToken } -ContentType 'application/json' -Body '{}' -TimeoutSec 12
    if (-not $flushResult.ok) { throw 'node did not confirm runtime flush' }
  } catch {
    throw 'runtime flush failed; backup was not created'
  } finally {
    $flushToken = $null
  }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupId = "runtime-$stamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$target = Join-Path $destinationRoot $backupId
$staging = Join-Path $destinationRoot ".$backupId.tmp"
New-Item -ItemType Directory -Path $staging | Out-Null
try {
  $included = @()
  foreach ($sourceFile in Get-ChildItem -LiteralPath $RuntimeDir -File -Force) {
    if ($sourceFile.Name -notmatch $allowedPattern) { continue }
    if (($sourceFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "runtime state symlinks are not backed up: $($sourceFile.Name)" }
    $destinationFile = Join-Path $staging $sourceFile.Name
    Copy-Item -LiteralPath $sourceFile.FullName -Destination $destinationFile
    if ($sourceFile.Name -match $allowedPattern) {
      $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
      if (-not $nodeCommand) { throw 'Node.js is required to sanitize the runtime snapshot before backup' }
      $sanitizer = Join-Path $PSScriptRoot '..\server\runtime-backup.js'
      & $nodeCommand.Source $sanitizer $destinationFile $destinationFile *> $null
      if ($LASTEXITCODE -ne 0) { throw 'runtime state backup sanitization failed' }
    }
    $copied = Get-Item -LiteralPath $destinationFile
    $included += [ordered]@{
      name = $copied.Name
      bytes = [long]$copied.Length
      sha256 = (Get-FileHash -LiteralPath $copied.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
  }
  $manifest = [ordered]@{
    formatVersion = 3
    createdAt = (Get-Date).ToUniversalTime().ToString('o')
    files = @($included)
    excluded = @('access.token', 'logs', 'camera frames', 'screenshots', 'APK/AAB', 'dependency caches', 'signing keys', 'precise location', 'continuous location history')
  }
  $manifestText = $manifest | ConvertTo-Json -Depth 8
  [IO.File]::WriteAllText((Join-Path $staging 'backup-manifest.json'), $manifestText, [Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $staging -Destination $target
  $staging = $null
  Write-Output "runtime backup created: $target"
} finally {
  if ($staging -and (Test-Path -LiteralPath $staging -PathType Container)) {
    $resolvedStaging = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $staging).Path)
    $resolvedDestination = [IO.Path]::GetFullPath($destinationRoot).TrimEnd('\') + '\'
    if ($resolvedStaging.StartsWith($resolvedDestination, [StringComparison]::OrdinalIgnoreCase)) {
      Remove-Item -LiteralPath $resolvedStaging -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}
