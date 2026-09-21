param(
  [Parameter(Mandatory = $true)]
  [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
$resolved = Resolve-Path -LiteralPath $ApkPath
$file = Get-Item -LiteralPath $resolved
$hash = Get-FileHash -LiteralPath $resolved -Algorithm SHA256

[PSCustomObject]@{
  path = $file.FullName
  bytes = $file.Length
  sha256 = $hash.Hash
} | ConvertTo-Json -Compress
