# NOTICE

## Licensing layers

This repository is deliberately **not** under a single license, because it
contains two layers with different legal obligations.

| Layer                          | Path                         | License                          | Why |
| ------------------------------ | ---------------------------- | -------------------------------- | --- |
| Kotlin application (UI/store)  | `composeApp/`, root Gradle   | **PolyForm Perimeter 1.0.0**     | The application code you asked to license under PolyForm Perimeter. |
| Rust native bridge             | `native/`                    | **AGPL-3.0-or-later**            | Statically links `typebit` (AGPL), so this crate must stay AGPL. |
| Core engine                    | `D:\RustProject\TypeBit` (external dependency) | AGPL-3.0-or-later (per its Cargo.toml) | The `typebit` crate by blueokanna / HyphenTeam. |

### Why the bridge must be AGPL

`native/` compiles `typebit` into a single `cdylib` (static linking). The
AGPL's linking boundary therefore reaches the bridge crate; distributing
that crate under a non-AGPL license would be a license violation. The Kotlin
side calls the bridge **through the JNI interface boundary** and does not
incorporate the AGPL code itself, so the Kotlin application is licensed
under the PolyForm Perimeter License as you requested.

If you later license `typebit` under more permissive terms, the `native/`
crate can be relicensed accordingly.

## Third-party notices

- **typebit 0.1.0** — AGPL-3.0-or-later — <https://github.com/blueokanna/TypeBit>
  Used as the download engine (BitTorrent v1/v2, DHT, PEX, web seeds).
- **courierust** — Apache-2.0 — HTTP client used inside `typebit`.
- **jni** — Apache-2.0/MIT — JNI bindings for the bridge crate.
- **getrandom** — MIT/Apache-2.0 — entropy source.
- **nextjson** — licensed per its crate metadata — JSON parsing in the bridge.
- **Kotlin / Compose Multiplatform / Material 3 / kotlinx-\*** — Apache-2.0 (JetBrains).
- **AndroidX** — Apache-2.0.

## Required Notice

```
Required Notice: Copyright (c) blueokanna. TypeBitTorrent is licensed under
the PolyForm Perimeter License 1.0.0 (application code) and
AGPL-3.0-or-later (native bridge). See LICENSE and this NOTICE.
```

## The PolyForm license text

The license text in `LICENSE` is reproduced verbatim from the PolyForm
Project and is provided under the [PolyForm Project
license](https://github.com/polyformproject/polyform-licenses). The
authoritative text lives at
<https://polyformproject.org/licenses/perimeter/1.0.0>.
