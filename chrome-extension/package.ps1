$zipName = "chrome-extension-packaged.zip"
$sourceDir = Get-Location
$filesToZip = @(
    "manifest.json",
    "background.js",
    "content.js",
    "popup.html",
    "popup.js",
    "icons",
    "README.md"
)

# Remove old zip if exists
if (Test-Path $zipName) {
    Remove-Item $zipName -Force
}

# Create new zip
Compress-Archive -Path $filesToZip -DestinationPath $zipName -Force

Write-Host "Packaging complete: $zipName"
