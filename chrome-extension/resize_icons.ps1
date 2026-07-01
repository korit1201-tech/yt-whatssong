Add-Type -AssemblyName System.Drawing

$sourcePath = "c:\Users\kory\.gemini\antigravity\playground\metallic-plasma\chrome-extension\icons\SOURCE2.png"
$iconsDir = "c:\Users\kory\.gemini\antigravity\playground\metallic-plasma\chrome-extension\icons"
$sizes = @(16, 48, 128)

if (-not (Test-Path $sourcePath)) {
    Write-Error "Source file not found at $sourcePath"
    exit 1
}

foreach ($size in $sizes) {
    $outputPath = Join-Path $iconsDir "icon$size.png"
    Write-Host "Generating $outputPath..."
    
    $image = [System.Drawing.Image]::FromFile($sourcePath)
    $format = [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    $bitmap = New-Object System.Drawing.Bitmap $size, $size, $format
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.DrawImage($image, 0, 0, $size, $size)
    
    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    
    $graphics.Dispose()
    $bitmap.Dispose()
    $image.Dispose()
}

Write-Host "Done!"
