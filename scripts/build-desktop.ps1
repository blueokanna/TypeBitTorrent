# Builds the Windows desktop native bridge and drops the DLL into the
# Compose resources so both `gradlew :composeApp:run` and packaged
# distributions find it.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/build-desktop.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$dll = Join-Path $native "target\release\typebit_native.dll"
$dest = Join-Path $root "composeApp\src\desktopMain\resources\native\typebit_native.dll"

Write-Host "[typebit] building desktop native bridge (release)..." -ForegroundColor Cyan
Push-Location $native
try {
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path $dll)) {
    throw "expected DLL not found: $dll"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
Copy-Item -Force $dll $dest
Write-Host "[typebit] DLL deployed to $dest" -ForegroundColor Green
