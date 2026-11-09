[CmdletBinding()]
param([string]$PinPath = '', [string]$PlatformDirectory = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This bootstrap requires Windows.' }
$Repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $PinPath) { $PinPath = Join-Path $Repository 'product/platform-windows.lock.json' }
if (-not $PlatformDirectory) { $PlatformDirectory = Join-Path $Repository '.tools/platform/idea-2026.1.4-windows' }
$PlatformDirectory = [IO.Path]::GetFullPath($PlatformDirectory)
$OwnedPlatform = [IO.Path]::GetFullPath((Join-Path $Repository '.tools/platform')) + [IO.Path]::DirectorySeparatorChar
if (-not $PlatformDirectory.StartsWith($OwnedPlatform, [StringComparison]::OrdinalIgnoreCase)) { throw 'SDK extraction must stay in this repository .tools/platform directory.' }
$Pin = Get-Content -LiteralPath $PinPath -Raw | ConvertFrom-Json
$Asset = $Pin.'windows-x64'
if ($Asset.url -notlike 'https://github.com/JetBrains/intellij-community/releases/download/*') { throw 'Only the pinned official OSS release is accepted.' }
$Marker = Join-Path $PlatformDirectory '.openidea-platform.json'
if (Test-Path -LiteralPath $PlatformDirectory) {
    if (-not (Test-Path -LiteralPath $Marker)) { throw 'Refusing to replace a directory without our verified SDK marker.' }
    $Existing = Get-Content -LiteralPath $Marker -Raw | ConvertFrom-Json
    if (($Existing | ConvertTo-Json -Depth 50 -Compress) -ne ($Pin | ConvertTo-Json -Depth 50 -Compress)) { throw 'Existing SDK marker differs from the current platform pin.' }
    if (-not (Test-Path -LiteralPath (Join-Path $PlatformDirectory 'product-info.json'))) { throw 'The pinned Windows SDK root is incomplete.' }
    Write-Output $PlatformDirectory
    exit 0
}
$DownloadDirectory = Join-Path $Repository '.tools/downloads'
New-Item -ItemType Directory -Force -Path $DownloadDirectory | Out-Null
$Archive = Join-Path $DownloadDirectory $Asset.name
function Test-PinnedArchive([string]$Path) {
    return ((Test-Path -LiteralPath $Path -PathType Leaf) -and (Get-Item -LiteralPath $Path).Length -eq $Asset.size -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Asset.sha256)
}
if (-not (Test-PinnedArchive $Archive)) {
    $Partial = "$Archive.partial"
    & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 1800 --output $Partial -- $Asset.url
    if ($LASTEXITCODE -ne 0 -or -not (Test-PinnedArchive $Partial)) { throw 'Official Windows SDK download failed size/SHA-256 verification.' }
    Move-Item -LiteralPath $Partial -Destination $Archive -Force
}
$Staging = Join-Path (Split-Path $PlatformDirectory -Parent) ('windows-sdk-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $Staging | Out-Null
try {
    Expand-Archive -LiteralPath $Archive -DestinationPath $Staging
    $Roots = @(Get-ChildItem -LiteralPath $Staging -Filter product-info.json -File -Recurse | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'lib')) -and (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'jbr/bin/java.exe'))
    })
    if ($Roots.Count -ne 1) { throw 'The authenticated ZIP must contain exactly one Windows IDE root.' }
    $Info = Get-Content -LiteralPath $Roots[0].FullName -Raw | ConvertFrom-Json
    if ($Info.version -ne $Pin.version -or @($Info.launch | Where-Object { $_.os -eq 'Windows' -and $_.arch -eq 'amd64' }).Count -ne 1) {
        throw 'The SDK version/Windows architecture does not match its pin.'
    }
    if ($Roots[0].DirectoryName -eq $Staging) {
        Move-Item -LiteralPath $Staging -Destination $PlatformDirectory
    } else {
        Move-Item -LiteralPath $Roots[0].DirectoryName -Destination $PlatformDirectory
    }
    Get-Content -LiteralPath $PinPath -Raw | Set-Content -LiteralPath $Marker -Encoding utf8NoBOM
} finally {
    if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
}
Write-Output $PlatformDirectory
