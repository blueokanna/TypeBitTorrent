# NOTICE

## Licensing

This repository is licensed under a single license layer, because the
upstream engine and the bridge share the same terms.

| Layer                          | Path                         | License                          | Why |
| ------------------------------ | ---------------------------- | -------------------------------- | --- |
| Kotlin application (UI/store)  | `composeApp/`, root Gradle   | **PolyForm Perimeter 1.0.0**     | The application code you asked to license under PolyForm Perimeter. |
| Rust native bridge             | `native/`                    | **PolyForm Perimeter 1.0.0**     | `typebit` 0.1.7 is now PolyForm Perimeter (not AGPL), so the statically-linked bridge can carry the same license. |
| Core engine                    | `typebit` 0.1.7 (local path dependency) | **PolyForm Perimeter 1.0.0** | The `typebit` crate by blueokanna / HyphenTeam (per its Cargo.toml `license-file`). |

`native/` compiles `typebit` into a single `cdylib` (static linking), so it
must stay under terms compatible with `typebit`'s license. Since
`typebit` 0.1.7 is PolyForm Perimeter — the same license as the Kotlin
application — the whole repository is uniformly PolyForm Perimeter 1.0.0.

## Third-party notices

- **typebit 0.1.7** — PolyForm Perimeter 1.0.0 — <https://github.com/blueokanna/TypeBit>
  Used as the download engine (BitTorrent v1/v2, DHT, PEX, web seeds,
  built-in anti-leech with peer bans, selective download, SOCKS5 proxy,
  UPnP/NAT-PMP, parallel piece verification, built-in rate limiting with a
  tolerance ceiling, byte-exact piece integrity, multi-tier trackers with
  UDP fast retransmit, stream-while-downloading, parallel multi-mirror
  web seeds).
- **courierust** — Apache-2.0 — HTTP client used inside `typebit`.
- **jni** — Apache-2.0/MIT — JNI bindings for the bridge crate.
- **getrandom** — MIT/Apache-2.0 — entropy source.
- **nextjson** — licensed per its crate metadata — JSON parsing in the bridge.
- **Kotlin / Compose Multiplatform / Material 3 / kotlinx-\*** — Apache-2.0 (JetBrains).
- **AndroidX** — Apache-2.0.

## Required Notice

```
Required Notice: Copyright (c) blueokanna. TypeBitTorrent is licensed under
the PolyForm Perimeter License 1.0.0. See LICENSE and this NOTICE.
```

## The PolyForm license text

The license text in `LICENSE` is reproduced verbatim from the PolyForm
Project and is provided under the [PolyForm Project
license](https://github.com/polyformproject/polyform-licenses). The
authoritative text lives at
<https://polyformproject.org/licenses/perimeter/1.0.0>.
