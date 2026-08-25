# TypeBitTorrent

A BitTorrent client for **Windows desktop and Android**, written in Kotlin
with **Jetpack Compose Multiplatform** (Material 3) on top of the
**typebit** Rust engine.

This is the GUI that `typebit` never shipped. The engine — BitTorrent
v1/v2, DHT, PEX, web seeds, a utility piece scheduler, a disk cache and
provable-download receipts — is a Rust core. This repo is the app around it:
one Kotlin codebase, two UIs (a qBittorrent-style desktop window and a
Material 3 Android app), and a thin JNI bridge in between.

```
┌──────────────────────────────┐        ┌─────────────────────────────┐
│  Kotlin / Compose (common)   │  JNI   │  Rust cdylib (native/)      │
│  UI · AppStore · Settings    │◄─────►│  Engine thread · Host I/O    │
│  Repositories · RSS · Search │        │  JSON protocol · meta mirror │
└──────────────────────────────┘        └──────────────┬──────────────┘
                                                       │ statically links
                                                ┌──────▼──────┐
                                                │ typebit 0.1.1 │ (PolyForm)
                                                └─────────────┘
```

## Why this exists

qBittorrent is great. BitComet is great. Neither is a library you can
embed, and both are huge C++ codebases. `typebit` is the opposite: a
`no_std` Rust core with a clean `Host` seam, but no UI at all. This project
is that missing UI, written so the same code runs on Windows and Android
without a line of platform UI code.

## Repository layout

```
composeApp/
  src/commonMain/    shared UI, store, models, settings, engine facade
                     (+ ui/monet HCT/CAM16 engine, ui/wallpaper engine)
  src/jvmShared/     JNI `actual external` declarations + JVM helpers
  src/androidMain/   Android entry, platform actuals, manifest
  src/desktopMain/   Desktop entry, platform actuals (AWT, resource loader)
native/              Rust JNI bridge crate (Host implementation + worker)
scripts/             build-desktop.ps1 · build-android.ps1
docs/                architecture.md · settings.md
```

## Building

### Prerequisites

- JDK 17
- Android SDK (for the Android target) + NDK `26.2.11394342` (for the `.so`)
- Rust stable (1.95+) with the Android targets:
  `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`
- The `typebit` crate (referenced from crates.io by `native/Cargo.toml`)

### 1. Native bridge

```powershell
# Windows desktop → composeApp/src/desktopMain/resources/native/typebit_native.dll
.\scripts\build-desktop.ps1

# Android → composeApp/src/androidMain/jniLibs/<abi>/libtypebit_native.so
$env:ANDROID_NDK_HOME = "C:\Users\you\AppData\Local\Android\Sdk\ndk\26.2.11394342"
.\scripts\build-android.ps1
```

### 2. Kotlin

```powershell
# Desktop
gradlew.bat :composeApp:run

# Android APK
gradlew.bat :composeApp:assembleDebug
```

## What works

- Add torrents from **.torrent files** (desktop file dialog / Android SAF)
  and from **magnet links** (also `ed2k://`, `thunder://`, `qqdl://` —
  the engine parses them; only BitTorrent actually downloads).
- **Selective download** (typebit 0.1.1): pick which files to download in
  the add dialog and change per-file priority (跳过/普通/高) at runtime in
  the 文件 tab — skipped files are never requested.
- Start / pause / resume / delete with persisted resume data (verified-piece
  bitfields, per-file priorities, per-task limits, DHT routing table survive
  restarts via `restore_torrent`).
- **Runtime tracker management**: add/remove announce URLs from the Tracker
  tab with no restart, and they persist across sessions.
- qBittorrent-shaped UI: state filters, categories, tags, a transfer table
  with progress/ratio/ETA/speeds, and a detail panel with 信息 / 文件 /
  Tracker / Peers / 分块 tabs (piece heat-grid from the real bitfield).
- A full settings dialog with the same categories as qBittorrent
  (行为 / 下载 / 连接 / 速度 / BitTorrent / WebUI / 高级 / RSS) — see
  `docs/settings.md` for which options are live vs. stored.
- Global download/upload speed limits enforced **by the engine's built-in
  token buckets** (typebit 0.1.1), with scheduled alternative limits.
- **Anti-leech engine** (typebit 0.1.1): client fingerprinting, per-peer
  reputation, corrupt-block / protocol-violation accounting, and **hard
  peer bans** — the status bar counts both detections and bans.
- **Parallel piece verification** (worker pool), **web seeds** (BEP-19),
  **SOCKS5 proxy** (outbound-only anonymity) and **UPnP/NAT-PMP** port
  mapping, all wired from the connection settings.
- DHT node count, engine log ring, RSS feed reader (real HTTP + XML),
  torrent search (local filter + open-in-browser engines).
- **Material You theming** (外观 settings): a from-scratch pure-Kotlin
  HCT/CAM16 engine (verified against the official material-color-utilities
  vectors, 6/6 tests) drives the whole MD3 palette from a **wallpaper**
  (gaussian blur + DIM + readability scrim, **fill/fit modes + vertical pan**,)
  with a live preview dialog), a manual seed-color override,
  light/dark/**AMOLED** modes and MD3 Expressive shapes/type/motion.
- **Tracker-list import**: paste an HTTPS URL (e.g. `ngosang/trackerslist`)
  in BitTorrent settings — the app fetches it, parses plain/JSON/HTML and
  appends the announce URLs to your extra-trackers.
- **Performance**: all engine I/O runs on a dedicated background executor
  (never the UI thread); each poll tick is a single batched native snapshot
  regardless of torrent count; bounded event/log queues and keyed lazy lists.

## Honest limitations (read before you file a bug)

`typebit 0.1.1`'s public API is still deliberately narrow, and this app does
not fake what the engine cannot report:

- **Per-torrent upload bytes/rate are not exposed** by the engine, so the
  UI shows `—` for them; global wire rates come from the bridge's counters.
- **Peer lists are not exposed** either. The Peers tab shows connection
  counts derived from engine events, not a fabricated table.
- **Magnet metadata**: once fetched, the engine emits `MetadataComplete`
  but still exposes no metainfo getter, so the bridge keeps its own
  add-time metadata mirror (name/files/trackers) for the UI.
- **Encryption mode, uTP, LSD** are stored settings with a qBittorrent-shaped
  UI; the 0.1.1 wire protocol is plaintext and the bridge implements none
  of these yet.
- The built-in **WebUI server is a roadmap item**; its settings are
  persisted but not served.

The goal of this project is correctness over surface area. Where a feature
cannot be done honestly with the 0.1.1 engine, it is labeled as such in the
UI and in this README rather than simulated.

## The JNI bridge

`native/` compiles `typebit` and a complete `std::net`/`std::fs` `Host` into
one `cdylib`. The engine runs on a dedicated Rust thread; Kotlin submits
commands over an mpsc channel and polls events. Because the bridge is a
separate process boundary (JNI), the Kotlin app never touches sockets,
files, or DNS — see `docs/architecture.md` for the full protocol and
threading model.

## License

This repository (application + bridge) is licensed under **PolyForm
Perimeter License 1.0.0** — the same license as the `typebit 0.1.1` engine
it statically links. See `LICENSE` and `NOTICE.md` for details.

| Layer | License |
| --- | --- |
| Kotlin application (`composeApp/`) | **PolyForm Perimeter 1.0.0** (see `LICENSE`) |
| Rust bridge (`native/`) | **PolyForm Perimeter 1.0.0** |
| `typebit` engine | **PolyForm Perimeter 1.0.0** © blueokanna / HyphenTeam |

---

*Built to be the app typebit deserves. Not affiliated with qBittorrent,
BitComet, or libtorrent.*
