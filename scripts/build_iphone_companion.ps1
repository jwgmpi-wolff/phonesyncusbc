[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\ios-companion'),
    [string]$Scheme = 'PhoneSyncCompanion',
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$iosRoot = (Resolve-Path $OutputDirectory).Path

function Test-Command {
    param([string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

if (-not $IsMacOS) {
    throw 'This script must run on macOS with Xcode installed. Windows cannot build or sign an iOS app natively.'
}

if (-not (Test-Command 'xcodegen')) {
    throw 'xcodegen was not found on PATH. Install it with: brew install xcodegen'
}

if (-not (Test-Command 'xcodebuild')) {
    throw 'xcodebuild was not found on PATH. Install Xcode or use the GitHub Actions build workflow.'
}

Push-Location $iosRoot
try {
    Write-Host 'Generating Xcode project from ios-companion/project.yml...'
    xcodegen generate

    Write-Host "Building $Scheme ($Configuration) for iPhone..."
    xcodebuild \
        -project PhoneSyncCompanion.xcodeproj \
        -scheme $Scheme \
        -sdk iphoneos \
        -configuration $Configuration \
        -derivedDataPath build \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        build

    $appPath = Join-Path $iosRoot "build/Build/Products/$Configuration-iphoneos/$Scheme.app"
    if (-not (Test-Path $appPath)) {
        throw "App bundle not found at $appPath"
    }

    $ipaRoot = Join-Path $iosRoot 'ipa'
    $payloadPath = Join-Path $ipaRoot 'Payload'
    $ipaPath = Join-Path $iosRoot 'PhoneSyncCompanion-unsigned.ipa'

    Remove-Item -Recurse -Force $ipaRoot -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $payloadPath | Out-Null
    Copy-Item -Recurse -Force $appPath $payloadPath

    Push-Location $ipaRoot
    try {
        Compress-Archive -Path (Join-Path $payloadPath '*') -DestinationPath $ipaPath -Force
    }
    finally {
        Pop-Location
    }

    Write-Host "Unsigned companion IPA created at: $ipaPath"
    Write-Host 'Install it on the real iPhone with Sideloadly or AltStore, then enable Local Network access.'
}
finally {
    Pop-Location
}
