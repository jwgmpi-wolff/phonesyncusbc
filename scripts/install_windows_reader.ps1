[CmdletBinding()]
param(
    [string]$Version = '2.4.0',
    [ValidateSet('win-arm64', 'win-x64')]
    [string]$RuntimeIdentifier,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $RuntimeIdentifier) {
    $RuntimeIdentifier = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'win-arm64' } else { 'win-x64' }
}
$zip = Join-Path $root "releases\PhoneSyncDataReader-$Version-$RuntimeIdentifier.zip"
if (-not (Test-Path -LiteralPath $zip)) {
    throw "Windows reader package was not found: $zip"
}

$installDirectory = Join-Path $env:LOCALAPPDATA 'Programs\Phone Sync Data Reader'
$running = @(Get-Process -Name 'PhoneSyncDataReader' -ErrorAction SilentlyContinue)
foreach ($process in $running) {
    if (-not $process.CloseMainWindow()) { $process.Kill() }
    if (-not $process.WaitForExit(5000)) { $process.Kill(); $process.WaitForExit() }
}
if (Test-Path -LiteralPath $installDirectory) {
    Remove-Item -LiteralPath $installDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
Expand-Archive -LiteralPath $zip -DestinationPath $installDirectory -Force

$executable = Join-Path $installDirectory 'PhoneSyncDataReader.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    throw "Windows reader executable was not present after extraction: $executable"
}
$shell = New-Object -ComObject WScript.Shell
$shortcuts = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Phone Sync Data Reader.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'Phone Sync Data Reader.lnk')
)
foreach ($shortcutPath in $shortcuts) {
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $executable
    $shortcut.WorkingDirectory = $installDirectory
    $shortcut.IconLocation = "$executable,0"
    $shortcut.Description = 'Browse recovered images, messages, SMS, and voicemails'
    $shortcut.Save()
}

Write-Host "Installed Phone Sync Data Reader ${Version}: $executable"
Write-Host "Desktop shortcut: $($shortcuts[0])"
Write-Host "Start-menu shortcut: $($shortcuts[1])"
if ($Launch) { Start-Process -FilePath $executable }