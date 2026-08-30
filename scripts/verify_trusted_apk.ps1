[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$TrustManifestPath
)

$ErrorActionPreference = 'Stop'

function Resolve-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    $sdk = $candidates | Select-Object -First 1
    if (-not $sdk) {
        throw 'Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
    }
    return $sdk
}

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $stream = [System.IO.File]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([System.BitConverter]::ToString($sha256.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
        } finally {
            $sha256.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Resolve-BuildTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SdkPath,

        [Parameter(Mandatory = $true)]
        [string]$ToolName
    )

    $buildToolsRoot = Join-Path $SdkPath 'build-tools'
    $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction Stop |
        Sort-Object {
            try { [version]$_.Name } catch { [version]'0.0' }
        } -Descending |
        ForEach-Object { Join-Path $_.FullName $ToolName } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    if (-not $tool) {
        throw "$ToolName was not found under $buildToolsRoot."
    }
    return $tool
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK was not found: $ApkPath"
}
if (-not (Test-Path -LiteralPath $TrustManifestPath)) {
    throw "APK trust manifest was not found: $TrustManifestPath"
}

$trust = Get-Content -LiteralPath $TrustManifestPath -Raw | ConvertFrom-Json
if ($trust.schemaVersion -ne 1) {
    throw "Unsupported APK trust manifest schema: $($trust.schemaVersion)"
}

$expectedHash = $trust.sha256.ToLowerInvariant()
$actualHash = Get-Sha256 -Path $ApkPath
if ($actualHash -ne $expectedHash) {
    throw "APK SHA-256 mismatch. Expected $expectedHash but found $actualHash."
}

$sdk = Resolve-AndroidSdk
$apksigner = Resolve-BuildTool -SdkPath $sdk -ToolName 'apksigner.bat'
$aapt = Resolve-BuildTool -SdkPath $sdk -ToolName 'aapt.exe'

$signatureOutput = @(& $apksigner verify --verbose --print-certs $ApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed: $($signatureOutput -join [Environment]::NewLine)"
}
$signerMatch = [regex]::Match(
    ($signatureOutput -join [Environment]::NewLine),
    'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
)
if (-not $signerMatch.Success) {
    throw 'The APK signer certificate SHA-256 digest could not be read.'
}
$actualSigner = $signerMatch.Groups[1].Value.ToLowerInvariant()
$expectedSigner = $trust.signerCertificateSha256.ToLowerInvariant()
if ($actualSigner -ne $expectedSigner) {
    throw "APK signer mismatch. Expected $expectedSigner but found $actualSigner."
}

$badgingOutput = @(& $aapt dump badging $ApkPath 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "APK package metadata could not be read: $($badgingOutput -join [Environment]::NewLine)"
}
$packageLine = $badgingOutput | Where-Object { $_ -match '^package:' } | Select-Object -First 1
$packageMatch = [regex]::Match(
    $packageLine,
    "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'"
)
if (-not $packageMatch.Success) {
    throw 'APK application ID and version could not be parsed.'
}

$actualApplicationId = $packageMatch.Groups[1].Value
$actualVersionCode = $packageMatch.Groups[2].Value
$actualVersionName = $packageMatch.Groups[3].Value
if ($actualApplicationId -ne $trust.applicationId) {
    throw "APK application ID mismatch. Expected $($trust.applicationId) but found $actualApplicationId."
}
if ($actualVersionCode -ne $trust.versionCode.ToString()) {
    throw "APK version code mismatch. Expected $($trust.versionCode) but found $actualVersionCode."
}
if ($actualVersionName -ne $trust.versionName) {
    throw "APK version name mismatch. Expected $($trust.versionName) but found $actualVersionName."
}

Write-Host "Trusted APK verified: $actualApplicationId $actualVersionName ($actualVersionCode)"
Write-Host "SHA-256: $actualHash"
Write-Host "Signer certificate SHA-256: $actualSigner"