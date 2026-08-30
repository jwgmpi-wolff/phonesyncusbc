[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [ValidateSet('Windows PC', 'Android', 'iPhone iPad', 'Camera IoT')]
    [string]$DeviceType = 'Android',

    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

$source = Get-Item -LiteralPath $SourcePath -ErrorAction Stop
$sourceFullPath = $source.FullName.TrimEnd('\')
$safeDeviceType = $DeviceType -replace '[^A-Za-z0-9._-]', '_'
$safeSourceName = $source.Name -replace '[^A-Za-z0-9._-]', '_'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'

if (-not $OutputPath) {
    $outputDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\Phone Sync\Owner Exports'
    $OutputPath = Join-Path $outputDirectory "OwnerApproved-$safeDeviceType-$safeSourceName-$timestamp.zip"
}

$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
if (-not $outputFullPath.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath must end in .zip.'
}
if ($source.PSIsContainer -and $outputFullPath.StartsWith("$sourceFullPath\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath cannot be inside the source directory.'
}
if (-not $source.PSIsContainer -and $outputFullPath -eq $source.FullName) {
    throw 'OutputPath cannot overwrite the source file.'
}
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Output already exists: $outputFullPath"
}

$outputParent = Split-Path -Parent $outputFullPath
if (-not $outputParent) {
    $outputParent = (Get-Location).Path
    $outputFullPath = Join-Path $outputParent (Split-Path -Leaf $outputFullPath)
}
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null

$files = if ($source.PSIsContainer) {
    @(Get-ChildItem -LiteralPath $source.FullName -File -Recurse -Force -ErrorAction Stop)
} else {
    @($source)
}
$sourceBytes = ($files | Measure-Object Length -Sum).Sum
if ($null -eq $sourceBytes) {
    $sourceBytes = 0
}

$tar = Get-Command tar.exe -ErrorAction Stop
$tarVersion = @(& $tar.Source --version 2>&1)
if ($LASTEXITCODE -ne 0 -or $tarVersion.Count -eq 0) {
    throw 'A working tar.exe with ZIP auto-format support is required. Update Windows or install bsdtar, then retry.'
}
$temporaryPath = Join-Path $outputParent (".{0}.partial.zip" -f [System.IO.Path]::GetFileNameWithoutExtension($outputFullPath))
try {
    & $tar.Source -a -c -f $temporaryPath -C $source.Parent.FullName $source.Name
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe exited with code $LASTEXITCODE. Ensure no source file is locked and retry."
    }
    Move-Item -LiteralPath $temporaryPath -Destination $outputFullPath
} catch {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    throw
}

$archive = Get-Item -LiteralPath $outputFullPath
$sha256 = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Owner-approved source ZIP: $($archive.FullName)"
Write-Host "Source files: $($files.Count)"
Write-Host "Source bytes: $sourceBytes"
Write-Host "Archive bytes: $($archive.Length)"
Write-Host "SHA-256: $sha256"
Write-Host 'In Phone Sync, select the matching external device and choose Import owner-approved backup / archive / export.'