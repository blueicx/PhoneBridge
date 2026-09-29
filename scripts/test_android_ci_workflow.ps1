$ErrorActionPreference = 'Stop'

$workflowPath = Join-Path $PSScriptRoot '../.github/workflows/ci.yml'
$workflow = Get-Content -LiteralPath $workflowPath -Raw
$expected = '(?m)^\s*script:\s*cd android && bash \./gradlew :app:connectedDebugAndroidTest --no-daemon --console=plain\s*$'

if ($workflow -notmatch $expected) {
    throw 'Android emulator CI must invoke gradlew through bash because the tracked wrapper is not executable on Linux.'
}

Write-Output 'Android emulator workflow shell contract passed.'
