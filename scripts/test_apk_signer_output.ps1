$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ApkSignerOutput.psm1') -Force

$compactDigest = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
$compactOutput = @"
Verifies
Signer #1 certificate DN: C=US, O=Android, CN=Test
Signer #1 certificate SHA-256 digest: $compactDigest
"@
$compact = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $compactOutput
if ($compact.CertificateSha256 -ne $compactDigest) { throw 'compact SHA-256 digest parsing failed' }
if ($compact.CertificateDn -ne 'C=US, O=Android, CN=Test') { throw 'certificate DN parsing failed' }

$crlfOutput = "Verifies`r`nSigner #1 certificate DN: C=US, O=Android, CN=Test`r`nSigner #1 certificate SHA-256 digest: $compactDigest`r`n"
$crlf = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $crlfOutput
if ($crlf.CertificateSha256 -ne $compactDigest) { throw 'CRLF signer output parsing failed' }

$v2Output = @"
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest:
$compactDigest
V2 Signer: certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567
"@
$v2 = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $v2Output
if ($v2.CertificateSha256 -ne $compactDigest) { throw 'V2 Signer multiline SHA-256 digest parsing failed' }
if ($v2.CertificateDn -ne 'C=US, O=Android, CN=Android Debug') { throw 'V2 Signer certificate DN parsing failed' }

$colonDigest = $compactDigest -replace '(.{2})(?!$)', '$1:'
$colonOutput = $compactOutput.Replace($compactDigest, $colonDigest)
$colon = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $colonOutput
if ($colon.CertificateSha256 -ne $compactDigest) { throw 'colon-separated SHA-256 digest parsing failed' }

$spacedDigest = $compactDigest -replace '(.{2})(?!$)', '$1 '
$spacedOutput = $compactOutput.Replace('Signer #1', '  signer #1').Replace($compactDigest, $spacedDigest)
$spaced = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $spacedOutput
if ($spaced.CertificateSha256 -ne $compactDigest) { throw 'space-separated SHA-256 digest parsing failed' }

$ansi = [char]27
$ansiOutput = $compactOutput.Replace("Signer #1 certificate SHA-256 digest:", "$ansi[32mSigner #1 certificate SHA-256 digest:$ansi[0m")
$ansiParsed = Get-PhoneBridgeApkSignerMetadata -SignatureOutput $ansiOutput
if ($ansiParsed.CertificateSha256 -ne $compactDigest) { throw 'ANSI-decorated signer output parsing failed' }

$invalidOutput = $compactOutput.Replace($compactDigest, '0123:invalid')
$rejected = $false
$rejectionMessage = ''
try { Get-PhoneBridgeApkSignerMetadata -SignatureOutput $invalidOutput | Out-Null }
catch {
  $rejectionMessage = $_.Exception.Message
  $rejected = $rejectionMessage -match 'digest could not be read'
}
if (-not $rejected) { throw 'malformed certificate digest was not rejected' }
if ($rejectionMessage -notlike '*Signer #1 certificate SHA-256 digest: 0123:invalid*') {
  throw 'malformed certificate digest diagnostic did not include its sanitized source line'
}

Write-Output 'APK signer metadata parser tests passed (legacy and V2 Signer formats, multiline, colon/space-separated, ANSI-decorated, malformed).'
