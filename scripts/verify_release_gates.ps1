param(
  [string]$ApkPath = (Join-Path $PSScriptRoot '..\android\app\build\outputs\apk\debug\app-debug.apk'),
  [ValidateSet('internal-debug', 'release')]
  [string]$Channel = 'internal-debug',
  [switch]$Signed,
  [switch]$AllowProtectedBranch,
  [switch]$RequireClean
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repository

$artifact = Get-Item -LiteralPath (Resolve-Path -LiteralPath $ApkPath)
$gradle = Get-Content -LiteralPath (Join-Path $repository 'android\app\build.gradle') -Raw
$versionNameMatch = [regex]::Match($gradle, 'versionName\s+"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradle, 'versionCode\s+(\d+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) { throw 'Android version metadata not found' }

$branch = (git branch --show-current).Trim()
if (-not $branch) { $branch = [string]$env:GITHUB_REF_NAME }
$commit = (git rev-parse HEAD).Trim()
$hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$relativePath = [IO.Path]::GetRelativePath($repository, $artifact.FullName).Replace('\', '/')
$manifest = [ordered]@{
  schemaVersion = 1
  generatedAt = (Get-Date).ToUniversalTime().ToString('o')
  channel = $Channel
  versionName = $versionNameMatch.Groups[1].Value
  versionCode = [int]$versionCodeMatch.Groups[1].Value
  branch = $branch
  commit = $commit
  signed = [bool]$Signed
  signingKeySource = if ($Signed) { 'external-keystore' } else { 'none' }
  allowProtectedBranch = [bool]$AllowProtectedBranch
  artifact = [ordered]@{
    kind = 'apk'
    path = $relativePath
    bytes = $artifact.Length
    sha256 = $hash
  }
}

$manifestJson = $manifest | ConvertTo-Json -Compress -Depth 8
$gateResult = $manifestJson | node (Join-Path $repository 'server\release-gates.js')
if ($LASTEXITCODE -ne 0) { throw "release gate failed: $gateResult" }

if ($RequireClean) {
  $status = git status --porcelain
  if ($status) { throw "worktree is not clean: $status" }
}

Write-Output $gateResult
