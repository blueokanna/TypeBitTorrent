# typebit_native

The JNI bridge between the TypeBitTorrent Kotlin app and the `typebit`
Rust engine. Licensed **AGPL-3.0-or-later** because it statically links the
AGPL `typebit` core (see `../NOTICE.md`).

## What it does

- Implements a complete `typebit::Host` on `std` (TCP/UDP/HTTP/disk + a
  token-bucket rate limiter for global speed limits).
- Runs the engine on a dedicated worker thread (100 ms tick); Kotlin
  submits commands over an mpsc channel and polls events as JSON.
- Mirrors `.torrent` metadata at add time (the 0.1.0 engine exposes no
  getters), so the app can render file lists, sizes and trackers.

## Layout

```
src/
  lib.rs      JNI_OnLoad + regression tests
  host.rs     NativeHost (sockets, UDP, HTTP via StdHost, disk, limiter)
  engine.rs   worker thread, Cmd protocol, config parsing, event JSON
  meta.rs     add-time metadata mirror
  json.rs     minimal JSON writer (no deps)
  jni_glue.rs 22 JNI entry points (class com.typebit.engine.NativeBridgeKt)
```

## Build

```powershell
# Windows desktop
.\scripts\build-desktop.ps1

# Android (all ABIs)
$env:ANDROID_NDK_HOME = "C:\...\ndk\26.2.11394342"
.\scripts\build-android.ps1
```

## Smoke test

```powershell
javac -encoding UTF-8 -d tools/smoke-out ../tools/smoke/SmokeTest.java
java -Djava.library.path=target/release -cp tools/smoke-out com.typebit.engine.SmokeTest
```

Exercises create → add (torrent + magnet) → start → query → events →
save/restore → remove against the real engine, with no UI and no Gradle.
