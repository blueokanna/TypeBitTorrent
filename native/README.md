# typebit_native

The JNI bridge between the TypeBitTorrent Kotlin app and the `typebit`
Rust engine. The bridge uses the repository's PolyForm Perimeter 1.0.0 license. [Third-party notices](../NOTICE.md)

## What it does

- Implements `typebit::Host` for TCP, UDP, HTTP(S) and storage. TypeBit enforces rate limits.
- Runs the engine on a dedicated worker thread (100 ms tick); Kotlin
  submits commands over an mpsc channel and polls events as JSON.
- Reads file lists and sizes from `Engine::metainfo`, and reports current trackers and peers in the UI snapshot. Restored magnets use their original URI and a hash-checked metadata cache.

## Layout

```
src/
  lib.rs      JNI_OnLoad + regression tests
  host.rs     NativeHost (sockets, Courierust HTTP client, disk)
  engine.rs   worker thread, Cmd protocol, config parsing, event JSON
  meta.rs     UI metadata and file renames
  json.rs     JSON writer
  jni_glue.rs JNI entry points for com.typebit.engine.NativeBridgeKt
```

## Build

```powershell
# Windows desktop
.\scripts\build-desktop.ps1

# Android (all ABIs)
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358"
.\scripts\build-android.ps1
```

## Smoke test

```powershell
# Run from the repository root after building the desktop library.
javac -encoding UTF-8 -d tools/smoke-out tools/smoke/SmokeTest.java
java "-Djava.library.path=native/target/release" -cp tools/smoke-out com.typebit.engine.SmokeTest
```

Exercises create → add (torrent + magnet) → start → query → events →
save/restore → remove against the real engine, with no UI and no Gradle.
