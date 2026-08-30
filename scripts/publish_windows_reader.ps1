[CmdletBinding()]
param(
    [string]$RuntimeIdentifier = 'win-arm64'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'windows\PhoneSyncDataReader\PhoneSyncDataReader.csproj'
$output = Join-Path $root "releases\PhoneSyncDataReader-$RuntimeIdentifier"

& dotnet publish $project `
    -c Release `
    -r $RuntimeIdentifier `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -o $output
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed with exit code $LASTEXITCODE."
}

Write-Host "Published Windows reader: $output"
