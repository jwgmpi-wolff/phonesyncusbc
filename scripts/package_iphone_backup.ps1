[CmdletBinding()]
param(
    [string]$BackupPath,
    [string]$ExtractorOutputPath,
    [string]$OutputPath,
    [switch]$List
)

$ErrorActionPreference = 'Stop'

function Get-AppleBackupDirectories {
    $roots = @(
        (Join-Path $env:USERPROFILE 'Apple\MobileSync\Backup'),
        (Join-Path $env:APPDATA 'Apple Computer\MobileSync\Backup'),
        (Join-Path $env:APPDATA 'Apple\MobileSync\Backup')
    ) | Select-Object -Unique

    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction Stop | Where-Object {
            Test-Path -LiteralPath (Join-Path $_.FullName 'Manifest.db') -PathType Leaf
        }
    }
}

$backups = @(Get-AppleBackupDirectories | Sort-Object LastWriteTimeUtc -Descending)
if ($List) {
    if ($backups.Count -eq 0) {
        Write-Host 'No Apple local backups were found.'
        exit 0
    }
    $backups | Select-Object FullName, LastWriteTimeUtc | Format-Table -AutoSize
    exit 0
}

if ($BackupPath -and $ExtractorOutputPath) {
    throw 'Choose either BackupPath or ExtractorOutputPath, not both.'
}

$sourceKind = 'Apple local backup'
if ($ExtractorOutputPath) {
    $selected = Get-Item -LiteralPath $ExtractorOutputPath -ErrorAction Stop
    if (-not $selected.PSIsContainer) {
        throw "ExtractorOutputPath must be a directory: $ExtractorOutputPath"
    }
    if (-not (Get-ChildItem -LiteralPath $selected.FullName -Force -Recurse -File | Select-Object -First 1)) {
        throw "ExtractorOutputPath is empty: $($selected.FullName)"
    }
    $sourceKind = 'third-party extractor output'
} elseif ($BackupPath) {
    $selected = Get-Item -LiteralPath $BackupPath -ErrorAction Stop
    if (-not $selected.PSIsContainer) {
        throw "BackupPath must be a directory: $BackupPath"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $selected.FullName 'Manifest.db') -PathType Leaf)) {
        throw "Manifest.db was not found under $($selected.FullName). Select the complete Apple backup directory."
    }
} elseif ($backups.Count -gt 0) {
    $selected = $backups[0]
} else {
    throw @'
No Apple local backup was found.

1. Connect the owned iPhone directly to this Windows PC.
2. Unlock it and approve Trust on the iPhone.
3. In Apple Devices, select the iPhone and create a complete local backup.
4. Run this script again. Phone Sync does not request or bypass an encrypted-backup password.
'@
}

if (-not $OutputPath) {
    $outputDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\Phone Sync\Owner Exports'
    $safeBackupName = $selected.Name -replace '[^A-Za-z0-9._-]', '_'
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $outputDirectory "OwnerApproved-iPhone-$sourceKind-$safeBackupName-$timestamp.zip"
}

$resolvedBackup = $selected.FullName.TrimEnd('\')
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
if (-not $outputFullPath.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath must end in .zip.'
}
if ($outputFullPath.StartsWith("$resolvedBackup\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath cannot be inside the selected source directory.'
}

$outputParent = Split-Path -Parent $outputFullPath
if (-not $outputParent) {
    $outputParent = (Get-Location).Path
    $outputFullPath = Join-Path $outputParent (Split-Path -Leaf $outputFullPath)
}
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
if (Test-Path -LiteralPath $outputFullPath) {
    throw "Output already exists: $outputFullPath"
}

$tar = Get-Command tar.exe -ErrorAction Stop
$tarVersion = @(& $tar.Source --version 2>&1)
if ($LASTEXITCODE -ne 0 -or $tarVersion.Count -eq 0) {
    throw 'A working tar.exe with ZIP auto-format support is required. Update Windows or install bsdtar, then retry.'
}
$temporaryPath = Join-Path $outputParent (".{0}.partial.zip" -f [System.IO.Path]::GetFileNameWithoutExtension($outputFullPath))
try {
    & $tar.Source -a -c -f $temporaryPath -C $selected.Parent.FullName $selected.Name
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe exited with code $LASTEXITCODE. Ensure Apple Devices has finished the backup and no file is locked."
    }
    Move-Item -LiteralPath $temporaryPath -Destination $outputFullPath
} catch {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    throw
}

$archive = Get-Item -LiteralPath $outputFullPath
$sha256 = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Owner-approved iPhone source ZIP: $($archive.FullName)"
Write-Host "Bytes: $($archive.Length)"
Write-Host "SHA-256: $sha256"
Write-Host 'Open Phone Sync, select the matching iPhone, then choose Import owner-approved backup / archive / export.'