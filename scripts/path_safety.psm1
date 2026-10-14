function Get-PhoneBridgePathComparison {
  if ([IO.Path]::DirectorySeparatorChar -eq [char]'\') {
    return [StringComparison]::OrdinalIgnoreCase
  }
  return [StringComparison]::Ordinal
}

function Get-PhoneBridgeDirectoryPrefix {
  param([Parameter(Mandatory=$true)][string]$Path)
  $resolved = [IO.Path]::GetFullPath($Path)
  $separator = [string][IO.Path]::DirectorySeparatorChar
  if (-not $resolved.EndsWith($separator, [StringComparison]::Ordinal)) {
    $resolved += $separator
  }
  return $resolved
}

function Test-PhoneBridgePathWithinDirectory {
  param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][string]$Directory
  )
  $resolved = [IO.Path]::GetFullPath($Path)
  $prefix = Get-PhoneBridgeDirectoryPrefix -Path $Directory
  return $resolved.StartsWith($prefix, (Get-PhoneBridgePathComparison))
}

Export-ModuleMember -Function Get-PhoneBridgePathComparison, Get-PhoneBridgeDirectoryPrefix, Test-PhoneBridgePathWithinDirectory
