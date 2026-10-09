# Running TypeBitTorrent on a NAS (飞牛 fnOS · Unraid · any Linux)

The desktop app is a Compose window and the Android app is, well, an app. A NAS
has neither a display nor a launcher — so the same client also runs **headless**
and serves its own WebUI:

```
bin/TypeBitTorrent --headless --bind=0.0.0.0 --port=18881 \
                   --data=/config --downloads=/downloads \
                   --username=admin --password='…'
```

Flags beyond the credentials (`--bind`, `--port`, `--data`, `--downloads`,
`--username`, `--password`) are `--frame-ancestors=<sources>`, which relaxes the
WebUI's `X-Frame-Options`/CSP default so a NAS dashboard may embed it (`*` for
"any ancestor" — the fnOS package passes that), and the matching environment
variables `TYPEBIT_*` for containers.

Nothing is reimplemented for the NAS: the headless mode boots the same
`AppStore`, the same Rust engine worker, the same `NativeHost` (sockets, disk
staging, HTTP/DNS workers) and the same persistence files. The browser talks to
`/api/…`, which executes real engine commands — add/pause/resume/remove,
per-file priorities and renames, tracker editing, live peer list, engine
statistics, settings, search, RSS and torrent creation.

---

## 1. What the WebUI can do

| Area | Available in the browser |
| --- | --- |
| Transfers | add magnet / upload `.torrent`, pause, resume, remove, live progress, speeds, ETA, ratio, seeds/peers |
| Files | per-file priority (skip / normal / high), bulk priority for every file, per-file rename |
| Trackers | add and remove announce URLs on a running torrent, plus a tracker subscription (see below) |
| Seeding | 校验本地数据 (recheck): hash files that are already on disk (from another client, a backup, or just created) and start seeding them |
| Peers | live swarm list (address, client fingerprint, country, phase, rates, in-flight blocks) |
| Info | infohash, save path, piece size/count, verified pieces, private flag, comment, creation date |
| Receipts | export a signed proof-of-download receipt for a torrent |
| Statistics | session rates/totals, DHT nodes, active trackers, LSD counters, listen port, UPnP/NAT-PMP state, resolver health, engine cache counters |
| Settings | save path, queue limits, connection (ports, DHT/PEX/LSD, encryption), speed limits, BitTorrent options, tracker subscription, WebUI options and password |
| Search | the same multi-site search the desktop app uses, results → add |
| RSS | feed list, article list, magnet extraction → add |
| Create torrent | server-side directory walk (keeps the directory structure), name, piece size (auto ≈2000 pieces), announce tiers, `source`, `private` (BEP-27), comment, live progress, cancel, download or add-and-seed |

Everything the desktop UI writes to disk lands in the same files: settings,
resume data (`.fastresume`-style state), receipts and the torrent records — so a
NAS instance and a desktop instance are interchangeable (point them at the same
`--data` directory only if you know what you are doing; the engine is a
single-instance-per-process worker).

### Tracker 订阅（自动更新）

A public trackerslist is the only way a client that cannot reach the DHT
bootstrap routers finds peers, and the list rots within weeks. *设置 →
BitTorrent · DHT · Tracker* therefore has two related fields:

* **附加 Tracker 列表** — your own announce URLs, one per line. Saving adds the
  new ones to every existing torrent and to new ones.
* **Tracker 订阅地址 / 自动更新间隔** — one or more trackerslist URLs
  (`https://cf.trackerslist.com/best.txt`, `https://raw.githubusercontent.com/ngosang/trackerslist/master/trackerslist.txt`,
  a private tracker's `all.txt`, …) and how often to refresh them. Leaving the
  field empty keeps the built-in community list (`cf.trackerslist.com/best.txt`),
  and `0` hours turns automatic updates off (the *立即更新订阅* button still works).
  The status line next to the button shows how many URLs the subscription
  currently holds and when it was last fetched.

The fetch runs on a background scope, never on the engine thread, and a failure
is non-destructive: the previously fetched list stays in place and the next poll
retries. The result is stored in `bitTorrent.subscribedTrackers`, so
`settings.json` always shows exactly what is being announced.

### 做种已有文件 / 校验本地数据

The engine creates every session with an **empty** piece bitfield: it knows
only what *it* downloaded. Adding a `.torrent` whose data you already have
(fetched by another client, restored from a backup, or the files a torrent was
just built from) therefore does **not** make it a seed — it would re-download
everything and upload nothing.

One pass fixes that. *校验本地数据* hashes the files under the save path against
the metainfo piece hashes and hands the verified set to the engine, after which
the torrent is complete and seeds:

* per-torrent **校验** button in the transfers list (progress and result appear
  in the status row; cancel works for large payloads),
* **automatic** after *制作种子 → 添加并做种* (desktop and WebUI), because that
  button means "make this data seedable",
* the desktop toolbar has the same action for the selected torrent
  (校验本地数据).

The verification reads the payload, so it takes as long as hashing those bytes
(≈100–200 MB/s on a NAS CPU). A piece that does not match — a corrupted file, a
half-written download — is simply left unverified, so the client keeps the good
pieces and re-downloads the rest.

### 哪些设置需要重启引擎
The engine reads some settings once, when it is created: listen port / random
port, DHT, LSD, UPnP / NAT-PMP, disk cache size, the resolver (`enableDoh`,
upstream list), IPv6 policy, LAN web seeds and the SOCKS5 proxy. The WebUI
labels those fields *（保存后自动重启引擎）*; saving one rebuilds the engine in
place — the WebUI stays up and every transfer resumes from its resume data.
Everything else (speed limits, concurrency, per-torrent defaults, trackers,
WebUI security) applies live. *重启引擎* next to *保存设置* forces a rebuild by
hand.

Switches that the bundled engine (`typebit 0.1.9`) has no support for — PEX and
MSE/encryption — are displayed read-only instead of pretending to work.

---

## 2. 飞牛 fnOS (native `.fpk` app)

fnOS packages are directories wrapped by `fnpack`:

```
packaging/fnos/typebittorrent/
├── manifest                    # INI, no extension — appname/version/platform/…
├── config/privilege            # {"defaults":{"run-as":"package"}}
├── config/resource             # shared folders (the download folder)
├── cmd/main                    # start | stop | status  (exit 0 / 3 / 1)
├── cmd/install_callback        # first-run WebUI password from the wizard
├── cmd/uninstall_init|callback # stop the client, honour the keep/delete answer
├── cmd/install_init|upgrade_*|config_*   # required lifecycle slots, no-ops here
├── wizard/install|uninstall    # App Center forms (password / keep data)
├── app/ui/config               # App-Center entry (iframe → the WebUI port)
├── app/ui/images/icon_*.png    # card icons
├── app/bin|lib|runtime         # payload: the self-contained app image
└── ICON.PNG, ICON_256.PNG      # 64×64 / 256×256 package icons
```

`fnOS` unpacks the *contents* of `app/` into `$TRIM_APPDEST`, so the payload ends
up as `$TRIM_APPDEST/bin/TypeBitTorrent` (+ `lib/`, `runtime/`) and `cmd/main`
execs exactly that — not an `app/bin/…` path.

Build **both** architectures on a Linux host (x86_64 for the tooling, the
aarch64 add-ons listed below):

```bash
# one-time: the official packaging tool
curl -fLO https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64
chmod +x fnpack-1.2.3-linux-amd64 && sudo mv fnpack-1.2.3-linux-amd64 /usr/local/bin/fnpack

# one-time for the arm64 package: an aarch64 JDK 17 + the cross linker
curl -fL -o jdk-arm64.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse'
mkdir -p ~/jdks/arm64 && tar xzf jdk-arm64.tar.gz -C ~/jdks/arm64 --strip-components=1
sudo apt install gcc-aarch64-linux-gnu python3-pil

# both packages (or: x86_64 / aarch64)
packaging/fnos/build-fpk.sh all
ls packaging/fnos/dist/     # typebittorrent_<ver>_x86.fpk  typebittorrent_<ver>_arm.fpk
```

`jpackage` cannot cross-build an app image, so the arm64 payload is assembled by
the same script to the same layout: the launcher and the runtime come from the
aarch64 JDK (`jdk.jpackage` jmod + a cross `jlink`), the Rust engine is
cross-compiled with `aarch64-linux-gnu-gcc`, and skiko's arm64 native replaces
the x64 one. `manifest`'s `platform=` (`x86` / `arm`) is what tells fnOS which
package belongs on which device — `fnpack` has no arch flag, and the App Center
expects one `.fpk` per architecture.

Install on the NAS (per the fnOS CLI docs):

```bash
appcenter-cli install-fpk packaging/fnos/dist/typebittorrent_0.1.9_x86.fpk   # x86_64 设备
appcenter-cli install-fpk packaging/fnos/dist/typebittorrent_0.1.9_arm.fpk   # arm64 设备
appcenter-cli start typebittorrent
```

Notes and honest caveats:

* **The package ships its own JRE.** fnOS supports `install_dep_apps=java-21-openjdk`,
  but bundling the runtime removes a whole class of "the NAS Java is too old"
  failures; the payload is ~150 MB larger.
* **The first-run WebUI password comes from the install wizard** (`wizard/install`,
  username `admin`). Installing from the CLI (`appcenter-cli install-fpk`) shows
  no form, so the first start generates one and keeps it in
  `<app data>/initial-password.txt` — on a default install that is
  `/vol1/@appdata/typebittorrent/initial-password.txt`. Change it later in the
  WebUI under *设置 → WebUI*.
* `manifest.service_port=18881` + `"port": "18881"` in `app/ui/config` make the
  App Center card open the WebUI in an iframe. That is why `cmd/main` starts the
  server with `--frame-ancestors=*`: the WebUI's default `X-Frame-Options: DENY`
  / `frame-ancestors 'none'` would otherwise leave the card blank. 18881 is the
  default because 8080 is usually taken on a NAS; if 18881 is taken too, change
  the port in both files *and* in the iframe URL, then reinstall — `cmd/main`
  refuses to start while the port is occupied and says so in the App Center.
* The download folder is the `typebittorrent/downloads` share (`TRIM_DATA_SHARE_PATHS`);
  settings, task records and resume data live in `TRIM_PKGVAR`
  (`/vol1/@appdata/typebittorrent`), so they survive upgrades. Uninstall asks
  whether to delete that runtime data — downloaded files are never touched.
* `cmd/main` stops the client with `SIGTERM` so the resume data is flushed and
  the engine worker is joined (`status` follows the documented 0/3/1 contract,
  and a start that cannot bind the port reports the log tail to the user).
* **`status` never guesses.** It identifies the service by the pid file *and*
  the process command line, tolerating an install root that is a symlink
  (`/var/apps/typebittorrent/target` → `/vol1/@appstore/…`) and a lifecycle call
  that arrives without `TRIM_APPDEST`/`TRIM_PKGVAR`. If it reported "stopped"
  while the client was alive, the App Center would start a second instance that
  cannot bind the port — the install then looks permanently broken ("启动失败",
  "已停用"). `start` and `stop` also reap orphaned instances (a crash followed
  by a restart, or an interrupted upgrade) that still hold the WebUI port.
* **If the app stops by itself**, the log tells you why: `<app data>/typebit.log`
  (default `/vol1/@appdata/typebittorrent/typebit.log`). The client's working
  directory is that same folder, so a JVM or engine crash also leaves
  `hs_err_pid*.log` next to it. A start after an unexpected exit logs
  `previous instance (pid …) is gone`, so the two events can be matched up.
* **Nothing is written to the download folder unless you ask for it**: the
  running data (settings, records, logs, crash dumps) lives in `TRIM_PKGVAR`.
* Icons: `ICON.PNG` (64×64) and `ICON_256.PNG` (256×256) at the package root plus
  `app/ui/images/icon_{64,256}.png` for the card, all rendered from
  `assets/typebittorrent.png`. They are committed, so `python3-pil` is only
  needed when you regenerate them.
* Publishing to the fnOS App Center currently goes through their developer
  group (there is no self-serve portal yet) — see
  <https://developer.fnnas.com/docs/quick-started/publish-application/>.

---

## 3. Unraid (Docker)

Unraid's own guidance is to run applications as containers and reserve plugins
for OS features, so this project ships a Docker image + a Community-Applications
style template instead of a `.plg`.

**Option A — manual template (works immediately, no feed):**

```bash
cp packaging/unraid/typebittorrent.xml /boot/config/plugins/dockerMan/templates-user/
```

Then Docker ▸ *Add Container* ▸ template `TypeBitTorrent`, set a WebUI password,
and start. The tree is the standard one:

| Setting | Container | Typical host |
| --- | --- | --- |
| WebUI | 18881/tcp | any free port |
| Peer port (TCP) | 6881/tcp | 6881 (must be reachable for inbound peers) |
| Peer port (UDP) | 6881/udp | 6881 (DHT / uTP) |
| Config | `/config` | `/mnt/user/appdata/typebittorrent` |
| Downloads | `/downloads` | `/mnt/user/downloads` |
| `TYPEBIT_PASSWORD` | env | your password (≥ 8 chars, required) |
| `PUID` / `PGID` | env | `99` / `100` (Unraid defaults) |

**Option B — Compose Manager / docker compose:**

```bash
cd packaging/docker
TYPEBIT_PASSWORD='change-me' docker compose up -d
```

**Option C — build the image yourself** (no registry needed):

```bash
docker build -f packaging/docker/Dockerfile -t typebittorrent:latest .
```

**Community Applications feed:** the CA submission gate requires an
OSI-approved license for the repository contents; this project is licensed
under *PolyForm Perimeter 1.0.0*, which is source-available but not
OSI-approved, so the feed will reject the repository until the license changes.
`packaging/unraid/ca_profile.xml` is provided so the repository can be submitted
the moment that changes; until then use Option A/B — they install exactly the
same container.

---

## 4. Plain Linux / systemd

```ini
# /etc/systemd/system/typebittorrent.service
[Unit]
Description=TypeBitTorrent (headless WebUI)
After=network-online.target
Wants=network-online.target

[Service]
User=typebit
Environment=TYPEBIT_PASSWORD=change-me
Environment=TYPEBIT_PORT=18881
Environment=TYPEBIT_DATA=/var/lib/typebittorrent
Environment=TYPEBIT_DOWNLOADS=/srv/downloads
ExecStart=/opt/typebit/bin/TypeBitTorrent --headless
Restart=on-failure
# SIGTERM (the default) is handled: resume data is flushed and the engine
# worker is joined before the process exits.
KillSignal=SIGTERM
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -m -d /var/lib/typebittorrent typebit
sudo install -d -o typebit -g typebit /srv/downloads
tar -xzf TypeBitTorrent-linux-x86_64.tar.gz -C /opt
sudo systemctl daemon-reload && sudo systemctl enable --now typebittorrent
```

`sigterm` → the CLI entry point runs the shutdown hook: the settings, torrent
records and resume data are written, `nativeDestroyEngine` joins the engine
thread (bounded), and only then does the process exit.

---

## 5. Configuration reference

| CLI / env | Meaning |
| --- | --- |
| `--headless`, `-H`, `TYPEBIT_HEADLESS=1` | run without a window (required on a NAS) |
| `--bind=`, `TYPEBIT_BIND` | interface to listen on (default `0.0.0.0`; use `127.0.0.1` behind a reverse proxy) |
| `--port=`, `TYPEBIT_PORT` | WebUI port (overrides the settings value) |
| `--data=`, `--config=`, `TYPEBIT_DATA`, `-Dtypebit.data.dir=` | state directory: settings, torrent records, resume data, receipts |
| `--downloads=`, `TYPEBIT_DOWNLOADS` | default save path used when no path is set in settings |
| `--username=`, `TYPEBIT_USERNAME` | WebUI user (default `admin`) |
| `--password=`, `TYPEBIT_PASSWORD` | WebUI password; hashed with PBKDF2-HMAC-SHA256 (120 000 iterations) and stored in settings. If neither a CLI nor env password exists **and** no hash is stored, a random one is generated and printed once at startup |

The engine's own network policy is *not* on this list: it lives in the settings
(连接 → 域名解析) and is read at engine start, so a NAS running in a container
with a broken DNS server can be given upstream resolvers from the WebUI, and a
NAS that must never touch its host network can switch LAN fetches off. The
counters for that live in `/api/stats` (`dns_*`).

Two container-specific notes on that screen:

* an upstream must be **addressed by IP** (`https://1.1.1.1/dns-query#cloudflare-dns.com`),
  because the appliance's own name server is exactly the thing being bypassed;
* with the provider list empty the resolver iterates from the root, which needs
  outbound UDP/53 and no CA store at all — the correct configuration for a
  minimal image that ships no `ca-certificates`. If encrypted upstreams are
  configured and no trust store can be read, they are dropped with a line in the
  log and resolution falls back to iterating. `TYPEBIT_CA_BUNDLE=/path/to/bundle.pem`
  points the trust store at a bundle explicitly instead.

### Security
* Sessions are 256-bit random tokens in an `HttpOnly`, `SameSite=Strict` cookie
  with a configurable TTL; changing the password invalidates every session.
* Every mutating request must carry `X-TypeBit: 1`. Browsers cannot add a custom
  header cross-site without a preflight, which this server never answers — that
  is the CSRF defence. `csrfProtection` can be turned off for unusual proxies.
* Failed logins are counted per client address and ban that address for
  `banDurationSec` after `maxAuthFailCount` attempts.
* `localHostAuth` (default on) lets a browser **on the NAS itself** skip login,
  exactly like qBittorrent's localhost bypass — turn it off on a shared box.
* `hostHeaderValidation` rejects requests whose `Host` header is not the bind
  address, a loopback name or the machine hostname.
* `clickjackingProtection` adds `X-Frame-Options: DENY`; the SPA is served with a
  CSP that allows only same-origin scripts (no `'unsafe-inline'`).
* Request bodies are capped at 32 MiB and static files come from a fixed
  whitelist — there is no path traversal surface.
* `httpsEnabled` does **not** make this server speak TLS (it would need
  certificate management in a NAS UI). Terminate TLS in a reverse proxy and set
  `reverseProxyEnabled` so session cookies are marked `Secure`.
* On the **desktop** build the WebUI listens on `127.0.0.1` only. Turning on
  `允许局域网访问` (settings → WebUI → `remoteAccess`) rebinds it to `0.0.0.0` —
  set a password first, because that toggle is the difference between "my
  machine" and "everyone on this Wi-Fi".

### Reverse proxy (Caddy example)

```
nas.example.com {
    reverse_proxy 127.0.0.1:18881
}
```

Then run the client with `--bind=127.0.0.1` and enable `reverseProxyEnabled` in
the WebUI settings.

---

## 6. Building the Linux payload

```bash
bash scripts/build-linux.sh
# → native/target/release/libtypebit_native.so
# → composeApp/build/compose/binaries/main/app/TypeBitTorrent   (app image, bundled JRE)
# → build-TypeBitTorrent-linux-<arch>.tar.gz
```

Requirements: JDK 17, Rust stable ≥ 1.95, `git`, `curl`, `tar`, `binutils`.
The Dockerfile and the fnOS package both consume the app image this produces,
so all three NAS paths ship byte-identical code.

Before shipping anything, prove the native libraries match the Kotlin bridge
(read [architecture.md](./architecture.md#the-jni-handshake-why-a-stale-library-is-loud-not-fatal)
for why this is not optional):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-native.ps1
# ABI revision: 2 (native == Kotlin)
# declared JNI entry points: 50
# desktop DLL : 50 entry points OK
# arm64-v8a : 50 entry points OK   … and the other three ABIs
```

The check fails if `JNI_ABI` and `EXPECTED_BRIDGE_ABI` disagree or if any
`expect fun native*` declared in Kotlin is missing from a library. A mismatch
links fine and then kills the JVM inside the first call, so this is the cheapest
possible place to catch it.
