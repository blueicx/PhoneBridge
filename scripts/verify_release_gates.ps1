param(
  [string]$ApkPath = (Join-Path $PSScriptRoot '..\android\app\build\outputs\apk\debug\app-debug.apk'),
  [ValidateSet('internal-debug', 'release')]
  [string]$Channel = 'internal-debug',
  [switch]$Signed,
  [string]$ExpectedCertificateSha256 = $env:PHONEBRIDGE_RELEASE_CERT_SHA256,
  [switch]$AllowProtectedBranch,
  [switch]$RequireClean,
  [string]$ManifestPath = ''
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ApkSignerOutput.psm1') -Force
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repository

$artifact = Get-Item -LiteralPath (Resolve-Path -LiteralPath $ApkPath)
$gradle = Get-Content -LiteralPath (Join-Path $repository 'android\app\build.gradle') -Raw
$versionNameMatch = [regex]::Match($gradle, 'versionName\s+"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradle, 'versionCode\s+(\d+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) { throw 'Android version metadata not found' }

function Resolve-AndroidTool([string]$Name) {
  $command = Get-Command $Name -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
  $buildTools = Join-Path $sdk 'build-tools'
  if (-not (Test-Path -LiteralPath $buildTools)) { return $null }
  foreach ($directory in Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object Name -Descending) {
    $candidate = Join-Path $directory.FullName $Name
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }
  return $null
}

$aapt = Resolve-AndroidTool 'aapt.exe'
if (-not $aapt) { $aapt = Resolve-AndroidTool 'aapt' }
$apksigner = Resolve-AndroidTool 'apksigner.bat'
if (-not $apksigner) { $apksigner = Resolve-AndroidTool 'apksigner' }
if (-not $aapt -or -not $apksigner) { throw 'Android aapt and apksigner are required for APK release verification' }

$badgingOutput = (& $aapt dump badging $artifact.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "aapt failed to inspect APK: $($badgingOutput.Trim())" }
$embedded = [regex]::Match($badgingOutput, "package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']*)'")
if (-not $embedded.Success) { throw 'APK manifest metadata could not be read with aapt' }
$embeddedPackageName = $embedded.Groups[1].Value
$embeddedVersionCode = [int]$embedded.Groups[2].Value
$embeddedVersionName = $embedded.Groups[3].Value
if ($embeddedPackageName -ne 'com.phonebridge') { throw "APK package mismatch: $embeddedPackageName" }
if ($embeddedVersionName -ne $versionNameMatch.Groups[1].Value -or $embeddedVersionCode -ne [int]$versionCodeMatch.Groups[1].Value) {
  throw "APK version mismatch: $embeddedVersionName/$embeddedVersionCode"
}

$signatureOutput = (& $apksigner verify --verbose --print-certs $artifact.FullName 2>&1 | Out-String)
$signatureVerified = $LASTEXITCODE -eq 0 -and $signatureOutput -match '(?m)^Verifies\s*$'
if (-not $signatureVerified) { throw "apksigner verification failed for $($artifact.Name)" }
$signatureScheme = if ($signatureOutput -match 'Verified using v3 scheme.*true') { 'v3' } elseif ($signatureOutput -match 'Verified using v2 scheme.*true') { 'v2' } elseif ($signatureOutput -match 'Verified using v1 scheme.*true') { 'v1' } else { 'unknown' }
$signerMetadata = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $signatureOutput
$certificateSha256 = $signerMetadata.CertificateSha256
$certificateDn = $signerMetadata.CertificateDn
if ($Channel -eq 'release') {
  if (-not $Signed) { throw 'release channel requires -Signed and an external keystore certificate' }
  if ($certificateDn -match '(?i)Android Debug') {
    throw 'release channel rejects the Android Debug certificate'
  }
  $expectedCertificate = ([string]$ExpectedCertificateSha256).Trim().ToLowerInvariant()
  if ($expectedCertificate -notmatch '^[0-9a-f]{64}$') {
    throw 'release channel requires PHONEBRIDGE_RELEASE_CERT_SHA256 or -ExpectedCertificateSha256'
  }
  if ($certificateSha256 -ne $expectedCertificate) {
    throw "APK signer certificate mismatch: $certificateSha256"
  }
}

$branch = (git branch --show-current).Trim()
if (-not $branch) { $branch = [string]$env:GITHUB_REF_NAME }
$commit = (git rev-parse HEAD).Trim()
$hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$relativePath = $artifact.FullName.Substring($repository.Length).TrimStart([char[]]@('\', '/')).Replace('\', '/')
$manifest = [ordered]@{
  schemaVersion = 2
  generatedAt = (Get-Date).ToUniversalTime().ToString('o')
  channel = $Channel
  versionName = $versionNameMatch.Groups[1].Value
  versionCode = [int]$versionCodeMatch.Groups[1].Value
  branch = $branch
  commit = $commit
  signed = [bool]($signatureVerified -and ($Channel -eq 'internal-debug' -or $Signed))
  signingKeySource = if ($Channel -eq 'release') { 'external-keystore' } else { 'debug-keystore' }
  allowProtectedBranch = [bool]$AllowProtectedBranch
  artifact = [ordered]@{
    kind = 'apk'
    path = $relativePath
    bytes = $artifact.Length
    sha256 = $hash
    packageName = $embeddedPackageName
    versionName = $embeddedVersionName
    versionCode = $embeddedVersionCode
    signatureVerified = $signatureVerified
    certificateSha256 = $certificateSha256
    certificateDn = $certificateDn
    signatureScheme = $signatureScheme
  }
}

$manifestJson = $manifest | ConvertTo-Json -Compress -Depth 8
$gateResult = $manifestJson | node (Join-Path $repository 'server\release-gates.js')
if ($LASTEXITCODE -ne 0) { throw "release gate failed: $gateResult" }

if ($ManifestPath) {
  $manifestFile = if ([IO.Path]::IsPathRooted($ManifestPath)) { $ManifestPath } else { Join-Path $repository $ManifestPath }
  New-Item -ItemType Directory -Path (Split-Path -Parent $manifestFile) -Force | Out-Null
  $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
}

if ($RequireClean) {
  $status = git status --porcelain
  if ($status) { throw "worktree is not clean: $status" }
}

Write-Output $gateResult
