function Get-PhoneBridgeApkSignerMetadata {
  param([Parameter(Mandatory)][string]$SignatureOutput)

  $normalizedOutput = [regex]::Replace($SignatureOutput, '\x1B\[[0-?]*[ -/]*[@-~]', '')
  $digestPattern = '(?im)^[ \t]*Signer #1 certificate SHA-256 digest:[ \t]*((?:[0-9a-fA-F]{64}|(?:[0-9a-fA-F]{2}[: -]){31}[0-9a-fA-F]{2}))[ \t]*\r?$'
  $digestMatch = [regex]::Match($normalizedOutput, $digestPattern)
  if (-not $digestMatch.Success) { throw 'APK signer certificate SHA-256 digest could not be read' }
  $digest = ($digestMatch.Groups[1].Value -replace '[: -]', '').ToLowerInvariant()
  if ($digest -notmatch '^[0-9a-f]{64}$') { throw 'APK signer certificate SHA-256 digest is malformed' }

  $dnMatch = [regex]::Match($normalizedOutput, '(?im)^[ \t]*Signer #1 certificate DN:[ \t]*(.+?)[ \t]*\r?$')
  if (-not $dnMatch.Success) { throw 'APK signer certificate DN could not be read' }
  return [pscustomobject]@{
    CertificateSha256 = $digest
    CertificateDn = $dnMatch.Groups[1].Value.Trim()
  }
}

Export-ModuleMember -Function Get-PhoneBridgeApkSignerMetadata
