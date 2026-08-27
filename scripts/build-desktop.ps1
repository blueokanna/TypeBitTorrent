# build-desktop.ps1 - builds the Windows desktop native DLL and deploys it to
# the compose resources so packaged apps carry the current engine.
#
# Referenced by the GitHub Actions workflow (`.github/workflows/build.yml`),
# VS Code tasks and the READMEs:
#   build-desktop.ps1 -> cargo build --release -> copies typebit_native.dll
#   into composeApp/src/desktopMain/resources/native/.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\build-desktop.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$desktopRes = Join-Path $root "composeApp\src\desktopMain\resources\native"
# cargo/rustup are often NOT on PATH in fresh shells (CI included) - prefer
# the full path and fall back to PATH.
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
if (-not (Test-Path $cargo)) { $cargo = "cargo.exe" }

Push-Location $native
try {
    Write-Host "==> building release DLL ..."
    & $cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    $dll = Join-Path $native "target\release\typebit_native.dll"
    if (-not (Test-Path $dll)) { throw "missing $dll" }
    New-Item -ItemType Directory -Force -Path $desktopRes | Out-Null
    Copy-Item $dll (Join-Path $desktopRes "typebit_native.dll") -Force
    Write-Host "    -> deployed typebit_native.dll"
} finally {
    Pop-Location
}
Write-Host "Done."
