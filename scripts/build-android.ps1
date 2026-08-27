# build-android.ps1 — builds the 4-ABI native library, packages the debug
# APK and (optionally) installs it on a connected device via adb.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1        # build + package
#   powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1 -Install   # + adb install
#   powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1 -Adb "C:\Users\blueo\adb\adb.exe"
param(
    [switch]$Install,
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$jniLibs = Join-Path $root "composeApp\src\androidMain\jniLibs"
# cargo/rustup are often NOT on PATH in fresh shells — use the full path.
$cargo = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
if (-not (Test-Path $cargo)) { $cargo = "cargo.exe" }
$env:ANDROID_NDK_HOME = "C:\Users\blueo\AppData\Local\Android\Sdk\ndk\30.0.15729638"
if (-not (Test-Path $env:ANDROID_NDK_HOME)) {
    Write-Error "NDK not found at $env:ANDROID_NDK_HOME — update this script."
}

$targets = @(
    @{ Target = "aarch64-linux-android";   Abi = "arm64-v8a" },
    @{ Target = "armv7-linux-androideabi"; Abi = "armeabi-v7a" },
    @{ Target = "x86_64-linux-android";    Abi = "x86_64" },
    @{ Target = "i686-linux-android";      Abi = "x86" }
)

# 1) Cross-compile the Rust cdylib for every ABI.
Push-Location $native
try {
    foreach ($t in $targets) {
        Write-Host "==> building $($t.Target) ..."
        & $cargo build --release --target $t.Target
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $($t.Target)" }
        $src = Join-Path $native "target\$($t.Target)\release\libtypebit_native.so"
        $dst = Join-Path $jniLibs "$($t.Abi)\libtypebit_native.so"
        New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
        Copy-Item $src $dst -Force
        Write-Host "    -> $dst"
    }
} finally {
    Pop-Location
}

# 2) Package the debug APK.
Push-Location $root
try {
    Write-Host "==> assembleDebug ..."
    & .\gradlew.bat :composeApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }
} finally {
    Pop-Location
}

$apk = Join-Path $root "composeApp\build\outputs\apk\debug\composeApp-debug.apk"
Write-Host "==> APK: $apk ($((Get-Item $apk).Length) bytes)"

# 3) Optional adb install.
if ($Install) {
    Write-Host "==> adb install -r ..."
    & $Adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    Write-Host "==> installed. Starting app ..."
    & $Adb shell am start -n com.typebit.app/com.typebit.MainActivity
}
Write-Host "Done."
