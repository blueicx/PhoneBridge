Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Foundation,ContentType=WindowsRuntime]

$bitmap = [System.Drawing.Bitmap]::FromFile('F:\CodexApps\PhoneBridge\mote_ui2.png')
$stream = New-Object System.IO.MemoryStream
$bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
$stream.Position = 0
$random = New-Object Windows.Storage.Streams.RandomAccessStream
Write-Output 'OCR engine availability:'
