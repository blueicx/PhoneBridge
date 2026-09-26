function Get-PhoneBridgeApkSignerMetadata {
  param([Parameter(Mandatory)][string]$SignatureOutput)

  $normalizedOutput = [regex]::Replace($SignatureOutput, '\x1B\[[0-?]*[ -/]*[@-~]', '')
  $digestValuePattern = '(?:[0-9a-fA-F]{64}|(?:[0-9a-fA-F]{2}[: -]){31}[0-9a-fA-F]{2})'
  $digestLabelPattern = '(?:Signer #1 certificate SHA-256 digest:|V[0-9]+ Signer: certificate SHA-256 digest:)'
  $digestPattern = "(?im)^[ \t]*$digestLabelPattern[ \t]*(?:\r?\n[ \t]*)?($digestValuePattern)[ \t]*\r?$"
  $digestMatch = [regex]::Match($normalizedOutput, $digestPattern)
  if (-not $digestMatch.Success) {
    $diagnosticLines = @($normalizedOutput -split '\r?\n' |
      Where-Object { $_ -match '(?i)signer|sha-?256|digest' } |
      Select-Object -First 8 |
      ForEach-Object {
        $line = ($_ -replace '[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]', '?').Trim()
        if ($line.Length -gt 512) { $line.Substring(0, 512) } else { $line }
      })
    $diagnosticContext = if ($diagnosticLines.Count) { $diagnosticLines -join ' | ' } else { '<no signer/digest lines found>' }
    throw "APK signer certificate SHA-256 digest could not be read; signer output: $diagnosticContext"
  }
  $digest = ($digestMatch.Groups[1].Value -replace '[: -]', '').ToLowerInvariant()
  if ($digest -notmatch '^[0-9a-f]{64}$') { throw 'APK signer certificate SHA-256 digest is malformed' }

  $dnLabelPattern = '(?:Signer #1 certificate DN:|V[0-9]+ Signer: certificate DN:)'
  $dnMatch = [regex]::Match($normalizedOutput, "(?im)^[ \t]*$dnLabelPattern[ \t]*(.+?)[ \t]*\r?$")
  if (-not $dnMatch.Success) { throw 'APK signer certificate DN could not be read' }
  return [pscustomobject]@{
    CertificateSha256 = $digest
    CertificateDn = $dnMatch.Groups[1].Value.Trim()
  }
}

Export-ModuleMember -Function Get-PhoneBridgeApkSignerMetadata
