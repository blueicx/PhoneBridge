param(
    [Parameter(Mandatory=$true)][string]$Text,
    [Parameter(Mandatory=$true)][string]$OutPath,
    [Parameter(Mandatory=$false)][string]$Voice = 'zh-CN-XiaoyiNeural'
)

$python = 'C:/Users/blueice/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$ffmpeg = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
if ($null -eq $ffmpeg) {
    $ffmpegFallback = 'E:/ffmpeg/ffmpeg-9.0-essentials_build/bin/ffmpeg.exe'
    if (Test-Path $ffmpegFallback) {
        $ffmpegPath = $ffmpegFallback
    } else {
        throw 'ffmpeg.exe was not found.'
    }
} else {
    $ffmpegPath = $ffmpeg.Source
}

$mediaPath = [System.IO.Path]::ChangeExtension($OutPath, '.mp3')
& $python -m edge_tts --voice $Voice --text $Text --write-media $mediaPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $mediaPath)) {
    throw "edge-tts failed with exit code $LASTEXITCODE."
}

& $ffmpegPath -hide_banner -loglevel error -y -i $mediaPath -ac 1 -ar 16000 -sample_fmt s16 $OutPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutPath)) {
    throw "Audio conversion failed with exit code $LASTEXITCODE."
}
