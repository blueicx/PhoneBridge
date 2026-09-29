param(
  [Parameter(Mandatory=$true)][string]$Source,
  [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\server'),
  [switch]$VerifyOnly,
  [switch]$ConfirmNodeStopped
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'privacy_archive.psm1') -Force

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf) -or [IO.Path]::GetExtension($sourcePath) -ne '.pbenc') {
  throw 'encrypted restore source must be a .pbenc file'
}
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) "PhoneBridgeEncryptedRestore-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
  $archivePath = Join-Path $temporaryRoot 'runtime.zip'
  $unpackedPath = Join-Path $temporaryRoot 'backup'
  Invoke-PhoneBridgeArchiveCli -Command decrypt -Source $sourcePath -Destination $archivePath
  Expand-Archive -LiteralPath $archivePath -DestinationPath $unpackedPath
  $restoreScript = Join-Path $PSScriptRoot 'restore_runtime.ps1'
  $restoreArguments = @{ Source = $unpackedPath; RuntimeDir = $RuntimeDir }
  if ($VerifyOnly) { $restoreArguments.VerifyOnly = $true }
  if ($ConfirmNodeStopped) { $restoreArguments.ConfirmNodeStopped = $true }
  & $restoreScript @restoreArguments
} finally {
  $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
  if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
    $resolvedTarget = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $temporaryRoot).Path)
    if ($resolvedTarget.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTarget) -match '^PhoneBridgeEncryptedRestore-[a-f0-9]{32}$') {
      Remove-Item -LiteralPath $resolvedTarget -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}
