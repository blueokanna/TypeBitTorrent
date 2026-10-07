# verify-native.ps1 - proves the shipped native libraries match the Kotlin code.
#
# The JNI ABI carries no type information: a Kotlin file that declares
# `nativeMakeTorrent(String)` and a native library that still implements
# `nativeMakeTorrent(String, String)` link perfectly and then die with
# SIGSEGV inside the call, because the callee reads a second argument that the
# caller never pushed. That is a hard process crash, not an exception, so the
# app cannot catch it - which is exactly why this check exists.
#
# It validates three things:
#   1. `JNI_ABI` in native/src/lib.rs == `EXPECTED_BRIDGE_ABI` in Kotlin,
#   2. every native function the Kotlin code declares is exported by the
#      Windows DLL and by each Android ABI's .so,
#   3. `nativeBridgeAbi` is exported (the runtime handshake that turns a latent
#      crash into a clear error message).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\verify-native.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\verify-native.ps1 -SkipAndroid

param(
    [switch]$SkipAndroid
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$native = Join-Path $root "native"
$jniLibs = Join-Path $root "composeApp\src\androidMain\jniLibs"
$dll = Join-Path $root "composeApp\src\desktopMain\resources\native\typebit_native.dll"
$runtime = Join-Path $root "composeApp\src\commonMain\kotlin\com\typebit\engine\NativeRuntime.kt"
$bridge = Join-Path $root "composeApp\src\commonMain\kotlin\com\typebit\engine\NativeBridge.kt"

$failures = New-Object System.Collections.Generic.List[string]

# ---------------------------------------------------------------- 1) ABI numbers
$sourceAbi = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $native "src\lib.rs")), 'pub const JNI_ABI: jint = (\d+);')
$kotlinAbi = [regex]::Match((Get-Content -Raw -LiteralPath $runtime), 'EXPECTED_BRIDGE_ABI\s*=\s*(\d+)')
if (-not $sourceAbi.Success) { $failures.Add("cannot find JNI_ABI in native/src/lib.rs") }
if (-not $kotlinAbi.Success) { $failures.Add("cannot find EXPECTED_BRIDGE_ABI in NativeRuntime.kt") }
if ($sourceAbi.Success -and $kotlinAbi.Success) {
    $a = [int]$sourceAbi.Groups[1].Value
    $b = [int]$kotlinAbi.Groups[1].Value
    if ($a -ne $b) {
        $failures.Add("ABI mismatch: native JNI_ABI=$a but Kotlin EXPECTED_BRIDGE_ABI=$b (bump both together)")
    } else {
        Write-Host "ABI revision: $a (native == Kotlin)"
    }
}

# ------------------------------------------------- 2) declared JNI entry points
# Every `expect fun nativeX(...)` in the common bridge is a function the JVM
# will look up by name in the loaded library, mangled at
# `Java_com_typebit_engine_NativeBridgeKt_nativeX`. The list is derived from the
# Kotlin source (not hand-maintained), so adding a native call and forgetting to
# implement it fails this check instead of the app.
$declared = [regex]::Matches((Get-Content -Raw -LiteralPath $bridge), '\bexpect fun (native\w+)') |
    ForEach-Object { "Java_com_typebit_engine_NativeBridgeKt_" + $_.Groups[1].Value } |
    Sort-Object -Unique
if ($declared.Count -eq 0) { $failures.Add("no 'expect fun native*' declarations found in NativeBridge.kt") }
Write-Host "declared JNI entry points: $($declared.Count)"

function Test-Exports {
    param([string]$Binary, [string]$Label)
    if (-not (Test-Path $Binary)) {
        $script:failures.Add("$Label missing: $Binary (run scripts\build-desktop.ps1 / build-android.ps1)")
        return
    }
    # Staleness. This script verifies binaries, it does not build them, so a
    # green run over a library that predates the Rust source is worse than no
    # check at all: it is a false negative that ships. Comparing against the
    # newest source file is enough to catch the real case (edit, then verify
    # without rebuilding), and it is why every failure message names the script
    # that rebuilds.
    $binaryTime = (Get-Item -LiteralPath $Binary).LastWriteTimeUtc
    $newest = Get-ChildItem -LiteralPath (Join-Path $script:native "src") -Filter *.rs -Recurse |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    $manifest = Get-Item -LiteralPath (Join-Path $script:native "Cargo.toml")
    if ($manifest.LastWriteTimeUtc -gt $binaryTime) { $newest = $manifest }
    if ($newest -and $newest.LastWriteTimeUtc -gt $binaryTime) {
        $script:failures.Add(
            "$Label is older than $($newest.Name) - rebuild before verifying " +
            "(scripts\build-desktop.ps1 / build-android.ps1)"
        )
        return
    }
    $isPe = $Binary.EndsWith(".dll", [System.StringComparison]::OrdinalIgnoreCase)
    if ($isPe) {
        if (-not (Test-Path $script:readobj)) {
            Write-Warning "${Label}: $($script:readobj) not found - symbol check skipped"
            return
        }
        # PE export table (a release DLL has no COFF symbol table, so nm is blind here).
        $symbols = (& $script:readobj --coff-exports $Binary 2>$null) -join "`n"
    } else {
        if (-not (Test-Path $script:nm)) {
            Write-Warning "${Label}: $($script:nm) not found - symbol check skipped"
            return
        }
        $symbols = (& $script:nm -D --defined-only $Binary 2>$null) -join "`n"
    }
    if (-not $symbols) {
        $script:failures.Add("$Label could not be inspected (no symbols read) - is the file truncated?")
        return
    }
    $missing = @($script:declared | Where-Object { $symbols -notmatch [regex]::Escape($_) })
    if ($missing.Count -gt 0) {
        $example = $missing | Select-Object -First 5
        $script:failures.Add("$Label is missing $($missing.Count) entry point(s), e.g. $($example -join ', ')")
    } else {
        Write-Host "$Label : $($script:declared.Count) entry points OK"
    }
}

# ------------------------------------------------------------- 3) the libraries
# Two LLVM tools from the NDK: `llvm-nm` reads ELF dynamic symbols, and
# `llvm-readobj --coff-exports` reads the PE export table (a release DLL has no
# COFF symbol table, so nm reports "no dynamic symbol table" there).
$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk) { $ndk = "C:\Users\blueo\AppData\Local\Android\Sdk\ndk\30.0.15729638" }
$llvmBin = Join-Path ($ndk.TrimEnd('\', '/')) "toolchains\llvm\prebuilt\windows-x86_64\bin"
$nm = Join-Path $llvmBin "llvm-nm.exe"
$readobj = Join-Path $llvmBin "llvm-readobj.exe"

Test-Exports -Binary $dll -Label "desktop DLL"

if (-not $SkipAndroid) {
    foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
        Test-Exports -Binary (Join-Path $jniLibs "$abi\libtypebit_native.so") -Label $abi
    }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "FAILED:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host ""
Write-Host "Native bridge verified." -ForegroundColor Green
