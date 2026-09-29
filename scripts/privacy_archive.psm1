function Invoke-PhoneBridgeArchiveCli {
  param(
    [Parameter(Mandatory=$true)][ValidateSet('encrypt', 'decrypt', 'verify')][string]$Command,
    [Parameter(Mandatory=$true)][string]$Source,
    [string]$Destination = ''
  )
  $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
  if (-not $nodeCommand) { throw 'Node.js is required for encrypted backup operations' }
  $cli = Join-Path $PSScriptRoot '..\server\privacy-archive-cli.js'
  if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) { throw 'privacy archive helper is missing' }
  $securePassphrase = Read-Host -Prompt '输入本次备份/恢复口令（至少 12 个字符）' -AsSecureString
  $pointer = [IntPtr]::Zero
  $plainPassphrase = $null
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassphrase)
    $plainPassphrase = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($Command -eq 'verify') {
      $plainPassphrase | & $nodeCommand.Source $cli $Command $Source *> $null
    } else {
      $plainPassphrase | & $nodeCommand.Source $cli $Command $Source $Destination *> $null
    }
    if ($LASTEXITCODE -ne 0) { throw 'encrypted archive operation failed; check the passphrase and archive integrity' }
  } finally {
    $plainPassphrase = $null
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ($securePassphrase) { $securePassphrase.Dispose() }
  }
}

Export-ModuleMember -Function Invoke-PhoneBridgeArchiveCli
