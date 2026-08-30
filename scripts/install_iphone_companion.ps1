[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$IpaPath,
    [string]$AppleId = '',
    [switch]$OpenSideloadly
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $IpaPath)) {
    throw "Signed IPA not found: $IpaPath"
}

$paths = @(
    'C:\Program Files\Sideloadly\sideloadly.exe',
    'C:\Program Files (x86)\Sideloadly\sideloadly.exe',
    "$env:LOCALAPPDATA\Programs\sideloadly\sideloadly.exe",
    "$env:LOCALAPPDATA\Programs\AltServer\AltServer.exe"
)

$runner = $paths | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if ($OpenSideloadly -or $null -ne $runner) {
    if (-not $runner) {
        throw 'Sideloadly/AltServer was not found. Install Sideloadly on Windows, then run this script again.'
    }

    Write-Host "Launching installer: $runner"
    Start-Process $runner -ArgumentList "--ipa \"$IpaPath\""
    if (-not [string]::IsNullOrWhiteSpace($AppleId)) {
        Start-Sleep -Seconds 2
        Write-Host "Use Apple ID: $AppleId when prompted for signing."
    }
    Write-Host 'After installation, trust the developer profile on the iPhone and enable Local Network permission.'
    return
}

Write-Host 'No Sideloadly/AltServer installation was detected.'
Write-Host 'Install Sideloadly on Windows from the official site, then use:'
Write-Host "  .\install_iphone_companion.ps1 -IpaPath \"$IpaPath\""
Write-Host 'On the iPhone: Settings > General > VPN & Device Management > Trust the profile.'
Write-Host 'Then: Settings > Privacy & Security > Local Network > Allow PhoneSync Companion.'
