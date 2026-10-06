# Architecture

This document describes how the pieces fit together, honestly — including
the seams and the trade-offs.

## Layers

```
┌──────────────────────────────────────────────────────────┐
│ Kotlin / Compose Multiplatform                            │
│                                                           │
│  ui/*        screens, components, theme (Material 3)      │
│  store/      AppStore — single StateFlow, unidirectional  │
│  engine/     TorrentEngine interface + JNI facade         │
│  data/       AppSettings · repositories · RSS             │
│  model/      domain models (Torrent, records, DTOs)       │
│  platform/   expect/actual seams (paths, picker, browser) │
│  webui/*     desktop-only: headless entry + HTTP server    │
└──────────────────────────┬───────────────────────────────┘
                           │ JNI (one cdylib for both targets)
┌──────────────────────────▼───────────────────────────────┐
│ native/ (Rust, PolyForm) │
│  jni_glue.rs  49 JNI entry points (thin, defensive)       │
│  engine.rs    worker thread · mpsc commands · JSON events │
│  host.rs       NativeHost — complete typebit::Host        │
│  make_torrent.rs  BEP-3/12/27 builder (progress + cancel) │
│  meta.rs       add-time metadata mirror                   │
│  json.rs       minimal JSON writer                        │
│  lib.rs        JNI_ABI handshake (see below)              │
└──────────────────────────┬───────────────────────────────┘
                           │ static link
┌──────────────────────────▼───────────────────────────────┐
│ typebit 0.1.9 (Rust, PolyForm) — the actual torrent engine │
└──────────────────────────────────────────────────────────┘
```

## Two front ends, one client

The engine + store are platform-agnostic, so a third target was nearly free:

* **Desktop / Android** — the Compose UI drives `AppStore`.
* **Headless (`--headless`)** — `webui/Headless.kt` boots the *same*
  `AppStore` and serves `webui/WebUiServer.kt`, a dependency-free
  `com.sun.net.httpserver` implementation. The SPA (`resources/webui/`) talks
  JSON to it; every endpoint calls the same store methods the Compose screen
  calls. Nothing is re-implemented per platform, which is why a NAS runs the
  full feature set.

## The JNI handshake (why a stale library is loud, not fatal)

JNI encodes no type information. If Kotlin declares `nativeMakeTorrent(String)`
while the loaded library still implements `nativeMakeTorrent(String, String)`,
the call links and then reads an argument that was never pushed — a SIGSEGV in
the middle of the JVM, i.e. the app "闪退" with no exception, no log line from
Kotlin, nothing to catch. It happened here during development (a stale
`typebit_native.dll`), which is why:

1. `native/src/lib.rs` defines `JNI_ABI`, exposed as `nativeBridgeAbi()`.
2. `NativeRuntime.EXPECTED_BRIDGE_ABI` must equal it; `NativeTorrentEngine`
   verifies the pair before the first real call and refuses with a clear
   message instead of crashing.
3. `scripts/verify-native.ps1` fails the build if the declared Kotlin
   `expect fun native*` set is not fully exported by the DLL and by all four
   Android ABIs, and if the two ABI numbers disagree.

Rule: bump `JNI_ABI` in the same commit as any native signature or JSON
contract change, and rebuild every shipped library.


## The engine boundary (why it looks like this)

`typebit`'s `Engine` is not thread-safe and must be driven from a single
thread. Its `Host` trait is the only seam the app can implement, and the
engine never exposes its sessions (peers, trackers, bitmaps) through the
public API. That drove three decisions:

1. **The bridge owns the engine.** `native/` compiles `typebit` and a full
   `std` `Host` into one `cdylib`. The engine runs on a dedicated Rust
   thread with a 100 ms tick.
2. **Commands go through a channel.** Kotlin never touches the engine
   directly. Every JNI call submits a `Cmd` over mpsc; blocking calls wait
   on a one-shot reply bounded by a 30 s timeout. Non-blocking calls
   (pause, resume, limits) are fire-and-forget.
3. **Metadata is mirrored at add time.** Because the engine has no getters,
   the bridge re-parses `.torrent` bytes with the public
   `metainfo::Torrent` API and keeps a `MetaRegistry`. Magnet torrents get a
   placeholder (name from `dn`) plus a `metadata_ready` flag flipped by the
   `MetadataComplete` event.

## NativeHost

A complete `typebit::Host` in `std`:

- **TCP** — outbound connects run on helper threads with
  `connect_timeout` and are handed back through a channel, so
  `tcp_connect` never blocks the engine tick. Established streams are
  non-blocking. Inbound accepts are drained by the worker before each tick.
- **UDP** — one non-blocking socket for DHT + UDP trackers.
- **HTTP(S)** — delegated to `typebit::host_std::StdHost`, whose
  `courierust` client has an in-tree TLS implementation (no system deps).
- **Disk** — `std::fs` with `set_len` preallocation and `sync_data` flush.
- **Global speed limits** — enforced by the engine's built-in token buckets
  (`EngineConfig::global_*_limit_bps`); the host only counts wire bytes for the
  status bar.
- **Web seeds** (BEP-19) — `http_get_range` delegates Range requests to the
  std host, which rejects bodies that are not exactly the requested window.
- **UPnP/NAT-PMP** — `local_ip` is discovered with the UDP-connect trick;
  `default_gateway`/`http_post` are best-effort (the mapper degrades
  gracefully when the platform cannot discover them).

## The Kotlin store

`AppStore` owns one `MutableStateFlow<AppState>`. UI dispatches actions;
the store calls the engine, persists records, and updates state. A poll
loop (cadence from settings, clamped 200–5000 ms) drains engine events,
refreshes per-torrent stats, computes rates from deltas, and persists
resume data every ~30 s.

Nothing outside the store mutates state. The engine facade
(`TorrentEngine`) is a narrow interface so the store never sees JNI or JSON
details.

## Persistence

| File (under the platform app-data dir) | Contents |
| --- | --- |
| `settings.json` | full `AppSettings` (atomic write) |
| `torrents.json` | app-level records: source (.torrent bytes base64 / magnet URI), category, tags, paused |
| `resume.bin` | engine resume blob (verified-piece bitfields + DHT table, via `SessionState::to_binary`) |
| `rss_feeds.json` | subscribed feed URLs |

On startup the app: loads settings → starts the engine → re-adds every
record → restores the resume blob → starts unpaused torrents → begins
polling.

## Honest data gaps

| UI feature | Status | Why |
| --- | --- | --- |
| Peer table | live (`session::PeerSnapshot`: address, client, country, phase, rates, in-flight blocks) | resolved in 0.1.9 — the earlier API only exposed counts |
| Per-torrent uploaded bytes | live (`engine.uploaded`) | resolved in 0.1.9 |
| Magnet file list | mirrored at add time / `MetadataComplete` flips `metadata_ready` | the engine emits the event without the info dict, so the bridge keeps its own mirror |
| Encryption / uTP | stored settings | the wire protocol is plaintext; uTP (BEP-29) exists but peers usually negotiate TCP |
| WebUI over HTTPS | not built in | TLS is expected to be terminated by a reverse proxy; the server has no certificate store |

These are documented in the README and marked in the UI; nothing is
simulated. Where a number genuinely cannot be reported, the UI shows `—`.
