[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')][string]$Version,
    [string]$OutputDirectory = 'build/release'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'The Windows release must be built and smoke-tested on Windows.' }
$Repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $Repository
if (-not [IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory = Join-Path $Repository $OutputDirectory }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$BuildRoot = [IO.Path]::GetFullPath((Join-Path $Repository 'build')) + [IO.Path]::DirectorySeparatorChar
if (-not $OutputDirectory.StartsWith($BuildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release output must stay inside this repository build directory.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$Proof = Join-Path $OutputDirectory 'windows-provenance'
New-Item -ItemType Directory -Force -Path $Proof | Out-Null
# GNU patch ships with Git for Windows but is not on the runner's default PATH.
$GitUsr = Join-Path $env:ProgramFiles 'Git/usr/bin'
if (Test-Path -LiteralPath (Join-Path $GitUsr 'patch.exe')) { $env:PATH = "$GitUsr;$env:PATH" }
& patch.exe --version | Set-Content -LiteralPath (Join-Path $Proof 'patch-version.log') -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) { throw 'GNU patch is required to build the pinned public source repairs.' }
$Sdk = & (Join-Path $PSScriptRoot 'bootstrap-platform.ps1')
if ($LASTEXITCODE -ne 0 -or -not $Sdk) { throw 'Windows SDK bootstrap failed.' }
$env:JAVA_HOME = Join-Path $Sdk 'jbr'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
& python -m unittest discover -s scripts/tests -v *>&1 | Tee-Object -FilePath (Join-Path $Proof 'platform-layout-tests.log')
if ($LASTEXITCODE -ne 0) { throw 'Native Windows layout/locking/javac argument-file tests failed.' }
$ToolPreparation = Join-Path $Repository '.tools/release-tools/windows-amd64'
$ToolReportPath = Join-Path $Proof 'tool-preparation.json'
& python scripts/bundle-go-tools.py --target windows-amd64 --output $ToolPreparation --report $ToolReportPath *>&1 | Tee-Object -FilePath (Join-Path $Proof 'tool-preparation.log')
if ($LASTEXITCODE -ne 0) { throw 'Bundled SDK/language/debug tools failed verified preparation.' }
$ToolReport = Get-Content -LiteralPath $ToolReportPath -Raw | ConvertFrom-Json
$Builder = $ToolReport.builderGoExecutable
if (-not (Test-Path -LiteralPath $Builder -PathType Leaf)) { throw 'The pinned Go builder was not produced.' }
$env:GOROOT = Split-Path (Split-Path $Builder -Parent) -Parent
$env:GOTOOLCHAIN = 'local'
$env:PATH = (Split-Path $Builder -Parent) + ';' + $env:PATH
$env:OPENGOLAND_GOPLS_TEST = Join-Path $ToolPreparation 'bin/gopls.exe'
$env:OPENGOLAND_GO_TEST = Join-Path $ToolPreparation 'go/bin/go.exe'
# The official windows-2025 image installs 64-bit MinGW and publishes this
# directory on PATH. Check the actual runner before running cgo/race tests.
$Gcc = 'C:\mingw64\bin\gcc.exe'
if (-not (Test-Path -LiteralPath $Gcc)) { $Gcc = (Get-Command gcc.exe -ErrorAction Stop).Source }
$env:CC = $Gcc
$env:CGO_ENABLED = '1'
$CompilerTarget = & $Gcc -dumpmachine
if ($LASTEXITCODE -ne 0 -or $CompilerTarget -notmatch '^x86_64-.*mingw32$') { throw 'Go race checks require the runner 64-bit MinGW C compiler.' }
& $Gcc --version *>&1 | Tee-Object -FilePath (Join-Path $Proof 'gcc-version.log')
if ($LASTEXITCODE -ne 0) { throw 'The Windows race C compiler does not execute.' }
& $Builder version *>&1 | Tee-Object -FilePath (Join-Path $Proof 'builder-version.log')
if ($LASTEXITCODE -ne 0) { throw 'The authenticated Go builder does not execute.' }
& .\gradlew.bat --no-daemon "-PplatformPath=$Sdk" "-PgoBuilder=$Builder" "-PreleaseVersion=$Version" test buildPlugin prepareSandbox verifyPluginStructure *>&1 | Tee-Object -FilePath (Join-Path $Proof 'gradle-package.log')
$GradleExit = $LASTEXITCODE
$KotlinResults = Join-Path $Repository 'build/test-results/test'
if (Test-Path -LiteralPath $KotlinResults -PathType Container) {
    Copy-Item -LiteralPath $KotlinResults -Destination (Join-Path $Proof 'kotlin-test-results') -Recurse -Force
}
if ($GradleExit -ne 0) { throw 'Windows native SDK tests/plugin build failed.' }
Push-Location -LiteralPath (Join-Path $Repository 'engine')
try {
    & $Builder tool pprof -help *>&1 | Set-Content -LiteralPath (Join-Path $Proof 'go-pprof-bootstrap.log') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'The official Go pprof tool could not be prepared before the timed profile oracle.' }
    & $Builder test -race -count=1 -p=1 -timeout=30m -json ./... | Set-Content -LiteralPath (Join-Path $Proof 'go-race.jsonl') -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw 'Windows Go race tests failed.' }
    & $Builder vet ./... *>&1 | Tee-Object -FilePath (Join-Path $Proof 'go-vet.log')
    if ($LASTEXITCODE -ne 0) { throw 'Windows Go vet failed.' }
} finally { Pop-Location }
$Product = Join-Path $Repository ('build/product-windows-' + [Guid]::NewGuid().ToString('N') + '/open Goland')
$Plugins = Join-Path $Repository 'build/idea-sandbox/opengoland/IC-2026.1.4/plugins'
& python scripts/package_windows.py --sdk $Sdk --plugins $Plugins --output $Product --version $Version | Set-Content -LiteralPath (Join-Path $Proof 'assembly.json') -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) { throw 'Windows product assembly/provenance/archive verification failed.' }

$InnoPin = Get-Content -LiteralPath (Join-Path $Repository 'product/windows-installer.lock.json') -Raw | ConvertFrom-Json
$InnoDirectory = Join-Path $Repository ('.tools/inno-setup-' + $InnoPin.version + '-' + [Guid]::NewGuid().ToString('N'))
$InnoDownload = Join-Path $Repository ('.tools/downloads/innosetup-' + $InnoPin.version + '.exe')
function Test-InnoAsset {
    return ((Test-Path -LiteralPath $InnoDownload -PathType Leaf) -and (Get-Item -LiteralPath $InnoDownload).Length -eq $InnoPin.size -and (Get-FileHash -LiteralPath $InnoDownload -Algorithm SHA256).Hash.ToLowerInvariant() -eq $InnoPin.sha256)
}
if (-not (Test-InnoAsset)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $InnoDownload -Parent) | Out-Null
    & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 600 --output $InnoDownload -- $InnoPin.url
    if ($LASTEXITCODE -ne 0 -or -not (Test-InnoAsset)) { throw 'Official Inno Setup asset failed size/SHA-256 authentication.' }
}
$Signature = Get-AuthenticodeSignature -LiteralPath $InnoDownload
if ($Signature.Status -ne 'Valid' -or $Signature.SignerCertificate.Subject -notmatch 'Pyrsys B\.V\.') { throw 'The pinned Inno Setup installer must have its valid official publisher signature.' }
$Compiler = Join-Path $InnoDirectory 'ISCC.exe'
$InstallTool = Start-Process -FilePath $InnoDownload -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$InnoDirectory`"") -PassThru -Wait
if ($InstallTool.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $Compiler)) { throw 'The authenticated Inno Setup compiler installation failed.' }
& $Compiler "/DAppSourceDir=$Product" "/DReleaseVersion=$Version" "/DReleaseOutputDir=$OutputDirectory" (Join-Path $PSScriptRoot 'open-goland.iss') *>&1 | Tee-Object -FilePath (Join-Path $Proof 'inno-compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup did not produce the real Windows installer.' }
$Installer = Join-Path $OutputDirectory "open-Goland-$Version-windows-x64-setup.exe"
if (-not (Test-Path -LiteralPath $Installer)) { throw 'Expected Windows installer was not produced.' }
$Installed = Join-Path $Repository ('build/installed-smoke-' + [Guid]::NewGuid().ToString('N') + '/open Goland')
$InstallLog = Join-Path $Proof 'install.log'
$Setup = Start-Process -FilePath $Installer -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$Installed`"", "/LOG=`"$InstallLog`"") -PassThru -Wait
if ($Setup.ExitCode -ne 0) { throw 'The generated installer did not install successfully.' }
try {
    & python (Join-Path $PSScriptRoot 'smoke-installed.py') --installed $Installed --output (Join-Path $Proof 'installed-smoke') *>&1 | Tee-Object -FilePath (Join-Path $Proof 'installed-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw 'The actual installed product smoke failed.' }
} finally {
    $Uninstaller = Join-Path $Installed 'unins000.exe'
    if (Test-Path -LiteralPath $Uninstaller) {
        $Uninstall = Start-Process -FilePath $Uninstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=`"$(Join-Path $Proof 'uninstall.log')`"") -PassThru -Wait
        if ($Uninstall.ExitCode -ne 0) { throw 'The installed product uninstaller failed.' }
    }
}
if (Test-Path -LiteralPath (Join-Path $Installed 'bin/opengoland64.exe')) { throw 'The smoke uninstall left the product launcher installed.' }
$Result = [ordered]@{
    schemaVersion = 1; version = $Version; target = 'windows-x64'; installer = (Split-Path $Installer -Leaf)
    installerSha256 = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash.ToLowerInvariant()
    installerBytes = (Get-Item -LiteralPath $Installer).Length; installerSigning = 'unsigned'
    installerTool = $InnoPin; compilerSha256 = (Get-FileHash -LiteralPath $Compiler -Algorithm SHA256).Hash.ToLowerInvariant()
    raceCompiler = $Gcc; raceCompilerTarget = $CompilerTarget; raceCompilerSha256 = (Get-FileHash -LiteralPath $Gcc -Algorithm SHA256).Hash.ToLowerInvariant()
    installExitCode = $Setup.ExitCode; uninstallExitCode = $Uninstall.ExitCode
    installedSmoke = 'installed-smoke/smoke.json'; sourceCommit = (& git rev-parse HEAD)
    limitations = @('Native version and project-frame startup and engine/tool workflows are not full interactive editor acceptance.', 'Windows process-tree cancellation remains subject to the documented backend limitations.')
}
$Result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $Proof 'release.json') -Encoding utf8NoBOM
$Result.installerSha256 + '  ' + (Split-Path $Installer -Leaf) | Set-Content -LiteralPath (Join-Path $OutputDirectory "open-Goland-$Version-windows-x64-setup.exe.sha256") -Encoding ascii
foreach ($Cache in @('go-cache', 'go-mod-cache')) {
    $OwnedCache = Join-Path $Proof "installed-smoke/$Cache"
    if (Test-Path -LiteralPath $OwnedCache) { Remove-Item -LiteralPath $OwnedCache -Recurse -Force }
}
Write-Output $Installer
