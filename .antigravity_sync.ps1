$src = "composeApp\build\dist\js\developmentExecutable"
if (Test-Path $src) {
    Get-ChildItem -Path $src | ForEach-Object {
        try {
            Copy-Item $_.FullName -Destination "." -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Host "Warning copying $($_.Name) to root: $_"
        }
        try {
            Copy-Item $_.FullName -Destination "docs" -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Host "Warning copying $($_.Name) to docs: $_"
        }
    }
    Write-Host "Sync complete!"
} else {
    Write-Host "Source directory $src not found!"
}
