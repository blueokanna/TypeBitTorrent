# NOTICE

## Licensing

This repository is licensed under a single license layer, because the
upstream engine and the bridge share the same terms.

| Layer                          | Path                         | License                          | Why |
| ------------------------------ | ---------------------------- | -------------------------------- | --- |
| Kotlin application (UI/store)  | `composeApp/`, root Gradle   | **PolyForm Perimeter 1.0.0**     | The application code you asked to license under PolyForm Perimeter. |
| Rust native bridge             | `native/`                    | **PolyForm Perimeter 1.0.0**     | The bridge uses the repository license. |
| Core engine                    | `typebit` 0.1.10             | **PolyForm Perimeter 1.0.0** | The `typebit` crate by blueokanna / HyphenTeam (per its Cargo.toml `license-file`). |

`native/` compiles `typebit` into a single `cdylib`. Third-party dependencies retain their respective licenses.

## Third-party notices

- **typebit 0.1.10** — PolyForm Perimeter 1.0.0 — <https://github.com/blueokanna/TypeBit>
  Used as the download engine (BitTorrent v1/v2, DHT, PEX, web seeds,
  built-in anti-leech with peer bans, selective download, SOCKS5 proxy,
  UPnP/NAT-PMP, parallel piece verification, built-in rate limiting with a
  tolerance ceiling, byte-exact piece integrity, multi-tier trackers with
  UDP fast retransmit, stream-while-downloading, parallel multi-mirror
  web seeds).
- **courierust 1.1.3** — PolyForm Perimeter 1.0.1. Required Notice: Copyright 2026 blueokanna and HyphenTeam. <https://github.com/blueokanna/Courierust>
- **recurse-x 0.2.6** — PolyForm Shield 1.0.0. Required Notice: Copyright 2026 blueokanna. <https://github.com/blueokanna/RecurseX>
- **jni** — Apache-2.0/MIT — JNI bindings for the bridge crate.
- **getrandom** — MIT/Apache-2.0 — entropy source.
- **nextjson** — Apache-2.0 — JSON parsing in the bridge.
- **Kotlin / Compose Multiplatform / Material 3 / kotlinx-\*** — Apache-2.0 (JetBrains).
- **AndroidX** — Apache-2.0.
- **Haze 2.1.0** provides background blur and glass refraction under Apache-2.0. <https://github.com/chrisbanes/haze>
- **MaterialKolor 5.0.1** generates seed-based Material palettes under the MIT license. <https://github.com/jordond/materialkolor>

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
