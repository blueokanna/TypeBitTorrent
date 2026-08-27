# deploy-native.ps1 — copies freshly-built native libraries into the app.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$jniLibs = Join-Path $root "composeApp\src\androidMain\jniLibs"
$desktopRes = Join-Path $root "composeApp\src\desktopMain\resources\native"

# Android .so per ABI.
$map = @{
    "aarch64-linux-android"   = "arm64-v8a"
    "armv7-linux-androideabi" = "armeabi-v7a"
    "x86_64-linux-android"    = "x86_64"
    "i686-linux-android"      = "x86"
}
foreach ($target in $map.Keys) {
    $src = Join-Path $native "target\$target\release\libtypebit_native.so"
    if (-not (Test-Path $src)) { throw "missing $src" }
    $dstDir = Join-Path $jniLibs $map[$target]
    New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
    Copy-Item $src (Join-Path $dstDir "libtypebit_native.so") -Force
    Write-Host "deployed -> $($map[$target])"
}

# Desktop DLL.
$dll = Join-Path $native "target\release\typebit_native.dll"
if (-not (Test-Path $dll)) { throw "missing $dll" }
New-Item -ItemType Directory -Force -Path $desktopRes | Out-Null
Copy-Item $dll (Join-Path $desktopRes "typebit_native.dll") -Force
Write-Host "deployed -> desktop DLL"
Write-Host "OK"
