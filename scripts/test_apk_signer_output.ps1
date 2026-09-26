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
try { Get-PhoneBridgeApkSignerMetadata -SignatureOutput $invalidOutput | Out-Null }
catch { $rejected = $_.Exception.Message -match 'digest could not be read' }
if (-not $rejected) { throw 'malformed certificate digest was not rejected' }

Write-Output 'APK signer metadata parser tests passed (compact, colon-separated, space-separated, ANSI-decorated, malformed).'
