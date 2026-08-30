[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$SourcePath,

    [ValidateSet('Windows PC', 'Android', 'iPhone iPad', 'Camera IoT')]
    [string]$DeviceType = 'Android',

    [string]$SourceName,

    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$schemaVersion = 1
$maximumManifestBytes = 64 * 1024 * 1024

if ($PSVersionTable.PSVersion.Major -lt 5) {
    throw 'RecoverByBackup requires Windows PowerShell 5.1 or PowerShell 7 or newer.'
}

function ConvertTo-SafeName {
    param([string]$Value)

    $safe = $Value -replace '[^A-Za-z0-9._-]', '_'
    if ([string]::IsNullOrWhiteSpace($safe)) { return 'backup' }
    return $safe
}

function Get-Sha256Text {
    param([string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-RecoverCategory {
    param([string]$Path)

    $normalized = '/' + ($Path -replace '\\', '/').TrimStart('/').ToLowerInvariant()
    $fileName = [System.IO.Path]::GetFileName($normalized)
    $extension = [System.IO.Path]::GetExtension($fileName).TrimStart('.').ToLowerInvariant()
    if ($normalized -match 'password|credential|passkey|webauthn|fido2|keepass|bitwarden|1password|lastpass|keychain' -or
        $extension -in @('kdb', 'kdbx', 'psafe3', '1pux', 'keychain-db')) { return 'PASSWORD_EXPORTS' }
    if ($normalized -match '/(sms|sms[_ -]?exports?)/|sms[-_ ]?(backup|export)|sms-mms-') { return 'SMS_EXPORTS' }
    if ($normalized -match '/(call[_ -]?logs?|call[_ -]?history)/|call[-_ ]?log') { return 'CALL_LOGS' }
    if ($extension -in @('ics', 'ical') -or $normalized -match '/calendars?/') { return 'CALENDAR' }
    if ($normalized -match '/(voicemail|voicemails|voicemail[_ -]?exports?)/|visual[-_ ]?voicemail') { return 'VOICEMAIL_EXPORTS' }
    if ($normalized -match 'whatsapp|signal|telegram|teams|webex|zoom|slack|discord|messenger|/chats?/') { return 'CHAT_EXPORTS' }
    if ($extension -in @('eml', 'emlx', 'mbox', 'pst', 'ost', 'msg') -or $normalized -match '/(email|emails|mail|mailbox)/') { return 'EMAIL_EXPORTS' }
    if ($normalized -match '/notifications?/') { return 'NOTIFICATION_EXPORTS' }
    if ($extension -in @('vcf', 'vcard')) { return 'CONTACTS' }
    if ($extension -in @('bmp', 'dng', 'gif', 'heic', 'heif', 'jpeg', 'jpg', 'png', 'tif', 'tiff', 'webp',
            '3g2', '3gp', 'avi', 'm4v', 'mkv', 'mov', 'mp4', 'mpeg', 'mpg', 'webm', 'wmv')) { return 'PHOTOS_AND_VIDEOS' }
    if ($normalized -match '/(system-info|system_information|device-info|diagnostics)/|bugreport|build\.prop') { return 'SYSTEM_INFORMATION' }
    if ($extension -in @('log', 'evtx', 'etl', 'dmp') -or $normalized -match '/logs?/|crash|tombstone') { return 'LOGS' }
    if ($extension -in @('cfg', 'conf', 'config', 'ini', 'plist', 'properties', 'toml', 'yaml', 'yml') -or
        $normalized -match '/(config|configs|configuration|settings)/') { return 'CONFIGURATION' }
    if ($extension -in @('db', 'sqlite', 'sqlite3', 'apk', 'aab') -or
        $normalized -match '/(android/data|application[_ -]?data|app[_ -]?data|apps)/') { return 'APPLICATION_DATA' }
    return 'DOCUMENTS'
}

function Copy-FileToArchive {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [System.IO.FileInfo]$File,
        [string]$ArchivePath
    )

    $entry = $Archive.CreateEntry($ArchivePath, [System.IO.Compression.CompressionLevel]::Optimal)
    $minimumZipTime = [datetime]'1980-01-01T00:00:00Z'
    $maximumZipTime = [datetime]'2107-12-31T23:59:58Z'
    $modifiedUtc = $File.LastWriteTimeUtc
    if ($modifiedUtc -lt $minimumZipTime) { $modifiedUtc = $minimumZipTime }
    if ($modifiedUtc -gt $maximumZipTime) { $modifiedUtc = $maximumZipTime }
    $entry.LastWriteTime = [System.DateTimeOffset]$modifiedUtc

    $input = [System.IO.File]::Open($File.FullName, 'Open', 'Read', 'ReadWrite')
    $output = $entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $buffer = New-Object byte[] (1024 * 1024)
    $bytes = 0L
    try {
        while (($read = $input.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $output.Write($buffer, 0, $read)
            [void]$sha.TransformBlock($buffer, 0, $read, $buffer, 0)
            $bytes += $read
        }
        [void]$sha.TransformFinalBlock([byte[]]::new(0), 0, 0)
        $hash = ([System.BitConverter]::ToString($sha.Hash)).Replace('-', '').ToLowerInvariant()
        return [pscustomobject]@{ Bytes = $bytes; Sha256 = $hash }
    } finally {
        $sha.Dispose()
        $output.Dispose()
        $input.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$sources = @($SourcePath | ForEach-Object { Get-Item -LiteralPath $_ -ErrorAction Stop })
if ($sources.Count -eq 0) { throw 'At least one source path is required.' }
if ([string]::IsNullOrWhiteSpace($SourceName)) {
    $SourceName = if ($sources.Count -eq 1) { $sources[0].Name } else { "$DeviceType composite backup" }
}
$safeDeviceType = ConvertTo-SafeName $DeviceType
$safeSourceName = ConvertTo-SafeName $SourceName
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutputPath) {
    $outputDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\RecoverByBackup'
    $OutputPath = Join-Path $outputDirectory "RecoverByBackup-$safeDeviceType-$safeSourceName-$timestamp.zip"
}
$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
if (-not $outputFullPath.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath must end in .zip.'
}
foreach ($source in $sources) {
    $sourceFullPath = $source.FullName.TrimEnd('\')
    if ($source.PSIsContainer -and $outputFullPath.StartsWith("$sourceFullPath\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputPath cannot be inside source directory: $sourceFullPath"
    }
    if (-not $source.PSIsContainer -and $outputFullPath -eq $source.FullName) {
        throw 'OutputPath cannot overwrite a source file.'
    }
}
if (Test-Path -LiteralPath $outputFullPath) { throw "Output already exists: $outputFullPath" }
$outputParent = Split-Path -Parent $outputFullPath
if (-not $outputParent) {
    $outputParent = (Get-Location).Path
    $outputFullPath = Join-Path $outputParent (Split-Path -Leaf $outputFullPath)
}
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null

$backupId = [guid]::NewGuid().ToString('N')
$externalPeerId = "recoverbybackup-$backupId"
$createdAt = [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$partialPath = Join-Path $outputParent (".{0}.{1}.partial.zip" -f [System.IO.Path]::GetFileNameWithoutExtension($outputFullPath), $backupId)
$manifestEntries = [System.Collections.Generic.List[object]]::new()
$sourceRoots = [System.Collections.Generic.List[object]]::new()
$categories = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$usedRootNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$sourceBytes = 0L
$fileIndex = 0
$maximumItems = 100000
$maximumItemBytes = 128L * 1024 * 1024 * 1024
$maximumTotalBytes = 4L * 1024 * 1024 * 1024 * 1024

try {
    $fileStream = [System.IO.File]::Open($partialPath, 'CreateNew', 'ReadWrite', 'None')
    $zip = [System.IO.Compression.ZipArchive]::new($fileStream, 'Create', $false, [System.Text.Encoding]::UTF8)
    try {
        foreach ($source in $sources) {
            $baseRootName = ConvertTo-SafeName $source.Name
            $rootName = $baseRootName
            $suffix = 2
            while (-not $usedRootNames.Add($rootName)) {
                $rootName = "$baseRootName-$suffix"
                $suffix += 1
            }
            $sourceRoots.Add([ordered]@{
                name = $rootName
                originalName = $source.Name
                kind = if ($source.PSIsContainer) { 'directory' } else { 'file' }
            })
            $files = if ($source.PSIsContainer) {
                @(Get-ChildItem -LiteralPath $source.FullName -File -Recurse -Force -ErrorAction Stop | Sort-Object FullName)
            } else {
                @($source)
            }
            foreach ($file in $files) {
                if ($fileIndex -ge $maximumItems) {
                    throw "RecoverByBackup supports at most $maximumItems files per archive. Split the backup into multiple archives."
                }
                if ($file.Length -gt $maximumItemBytes) {
                    throw "File exceeds the 128 GB per-file limit: $($file.FullName)"
                }
                if ($sourceBytes + $file.Length -gt $maximumTotalBytes) {
                    throw 'The selected files exceed the 4 TB archive verification limit.'
                }
                $relativePath = if ($source.PSIsContainer) {
                    $file.FullName.Substring($source.FullName.TrimEnd('\').Length).TrimStart('\')
                } else {
                    $file.Name
                }
                $normalizedRelativePath = ($relativePath -replace '\\', '/').Trim('/')
                if ([string]::IsNullOrWhiteSpace($normalizedRelativePath) -or
                    $normalizedRelativePath.Split('/') -contains '..') {
                    throw "Unsafe source-relative path: $relativePath"
                }
                $archivePath = "payload/$rootName/$normalizedRelativePath"
                $copy = Copy-FileToArchive -Archive $zip -File $file -ArchivePath $archivePath
                if ($copy.Bytes -ne $file.Length) {
                    throw "Source file changed while being archived: $($file.FullName)"
                }
                $category = Get-RecoverCategory $normalizedRelativePath
                [void]$categories.Add($category)
                $fileIndex += 1
                $sourceBytes += $copy.Bytes
                $modifiedAt = [System.DateTimeOffset]$file.LastWriteTimeUtc
                $fingerprint = Get-Sha256Text "$externalPeerId|$archivePath|$($copy.Bytes)|$($modifiedAt.ToUnixTimeMilliseconds())|$($copy.Sha256)"
                $sensitive = $category -eq 'PASSWORD_EXPORTS'
                $manifestEntries.Add([ordered]@{
                    index = $fileIndex
                    category = $category
                    peerId = $externalPeerId
                    sourceFingerprint = $fingerprint
                    sourceItem = "/RecoverByBackup/$safeSourceName/$rootName/$normalizedRelativePath"
                    sourceSize = $copy.Bytes
                    sourceModifiedAtEpochMillis = $modifiedAt.ToUnixTimeMilliseconds()
                    recoveredAtEpochMillis = $createdAt
                    archivePath = $archivePath
                    bytes = $copy.Bytes
                    sha256 = $copy.Sha256
                    sensitive = $sensitive
                    handling = if ($sensitive) { 'COPIED_OPAQUE_NO_DECRYPTION' } else { 'PRESERVED_WITH_SHA256' }
                })
                Write-Progress -Activity 'Creating RecoverByBackup archive' -Status $normalizedRelativePath -PercentComplete -1
            }
        }
        if ($manifestEntries.Count -eq 0) { throw 'The selected source paths contain no files.' }
        $manifest = [ordered]@{
            format = 'RecoverByBackup'
            schemaVersion = $schemaVersion
            backupId = $backupId
            externalPeerId = $externalPeerId
            sourceName = "RecoverByBackup $SourceName"
            deviceType = $DeviceType
            createdAtEpochMillis = $createdAt
            itemCount = $manifestEntries.Count
            sourceBytes = $sourceBytes
            includedCategories = @($categories | Sort-Object)
            sourceRoots = @($sourceRoots)
            coverage = [ordered]@{
                basis = 'OWNER_SUPPLIED_FILES_ONLY'
                completeDeviceImage = $false
                protectedDataBypassAttempted = $false
                statement = 'Includes every readable file in the selected source paths. Data not exposed or exported by the source OS, account, carrier, or application is not represented as recovered.'
            }
            entries = @($manifestEntries)
        }
        $manifestJson = $manifest | ConvertTo-Json -Depth 10 -Compress
        $manifestByteCount = [System.Text.Encoding]::UTF8.GetByteCount($manifestJson)
        if ($manifestByteCount -gt $maximumManifestBytes) {
            throw "RecoverByBackup manifest exceeds the 64 MB Reader limit. Split the backup into multiple archives."
        }
        $manifestEntry = $zip.CreateEntry('recoverbybackup-manifest.json', [System.IO.Compression.CompressionLevel]::Optimal)
        $writer = [System.IO.StreamWriter]::new($manifestEntry.Open(), [System.Text.UTF8Encoding]::new($false))
        try {
            $writer.Write($manifestJson)
        } finally {
            $writer.Dispose()
        }
    } finally {
        $zip.Dispose()
        $fileStream.Dispose()
    }
    $verify = [System.IO.Compression.ZipFile]::OpenRead($partialPath)
    try {
        if (-not ($verify.Entries | Where-Object FullName -eq 'recoverbybackup-manifest.json')) {
            throw 'RecoverByBackup manifest verification failed.'
        }
        if (($verify.Entries | Where-Object { -not $_.FullName.EndsWith('/') }).Count -ne $manifestEntries.Count + 1) {
            throw 'RecoverByBackup entry-count verification failed.'
        }
    } finally {
        $verify.Dispose()
    }
    Move-Item -LiteralPath $partialPath -Destination $outputFullPath
} catch {
    Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
    throw
} finally {
    Write-Progress -Activity 'Creating RecoverByBackup archive' -Completed
}

$archive = Get-Item -LiteralPath $outputFullPath
$sha256 = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "RecoverByBackup archive: $($archive.FullName)"
Write-Host "Backup ID: $backupId"
Write-Host "Included files: $($manifestEntries.Count)"
Write-Host "Included source bytes: $sourceBytes"
Write-Host "Archive bytes: $($archive.Length)"
Write-Host "Archive SHA-256: $sha256"
Write-Host 'Coverage is limited to files readable from the owner-supplied source paths; protected or cloud-only data is not inferred.'