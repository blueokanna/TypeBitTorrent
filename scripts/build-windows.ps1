# build-windows.ps1 — builds the Windows packages:
#   1. TypeBitTorrent.exe   — portable launcher (no install, double-click)
#   2. TypeBitTorrent-*.exe — EXE installer
#   3. TypeBitTorrent-*.msi — MSI installer
#
# The desktop DLL is rebuilt and deployed to the compose resources first, so
# the packages carry the current native engine.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$desktopRes = Join-Path $root "composeApp\src\desktopMain\resources\native"
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
if (-not (Test-Path $cargo)) { $cargo = "cargo.exe" }

# 1) Rebuild the desktop DLL and deploy it to the packaged resources.
Push-Location $native
try {
    Write-Host "==> building release DLL ..."
    & $cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    $dll = Join-Path $native "target\release\typebit_native.dll"
    New-Item -ItemType Directory -Force -Path $desktopRes | Out-Null
    Copy-Item $dll (Join-Path $desktopRes "typebit_native.dll") -Force
    Write-Host "    -> deployed typebit_native.dll"
} finally {
    Pop-Location
}

# 2) Portable distribution (bundles the DLL + JVM runtime + launcher exe).
Push-Location $root
try {
    Write-Host "==> createDistributable (portable) ..."
    & .\gradlew.bat :composeApp:createDistributable --console=plain
    if ($LASTEXITCODE -ne 0) { throw "createDistributable failed" }

    Write-Host "==> packageExe (EXE installer) ..."
    & .\gradlew.bat :composeApp:packageExe --console=plain
    if ($LASTEXITCODE -ne 0) { throw "packageExe failed" }

    Write-Host "==> packageMsi (MSI installer) ..."
    & .\gradlew.bat :composeApp:packageMsi --console=plain
    if ($LASTEXITCODE -ne 0) { throw "packageMsi failed" }
} finally {
    Pop-Location
}

$bin = Join-Path $root "composeApp\build\compose\binaries\main"
$exeDir = Join-Path $bin "exe"
$msiDir = Join-Path $bin "msi"
$appDir = Join-Path $bin "app"
Write-Host "==> portable: $appDir\TypeBitTorrent\TypeBitTorrent.exe"
Write-Host "==> EXE installer: $exeDir"
Write-Host "==> MSI installer: $msiDir"
Write-Host "Done."
