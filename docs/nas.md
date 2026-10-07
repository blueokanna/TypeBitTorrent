# Running TypeBitTorrent on a NAS (飞牛 fnOS · Unraid · any Linux)

The desktop app is a Compose window and the Android app is, well, an app. A NAS
has neither a display nor a launcher — so the same client also runs **headless**
and serves its own WebUI:

```
bin/TypeBitTorrent --headless --bind=0.0.0.0 --port=8080 \
                   --data=/config --downloads=/downloads \
                   --username=admin --password='…'
```

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
| Trackers | add and remove announce URLs on a running torrent |
| Peers | live swarm list (address, client fingerprint, country, phase, rates, in-flight blocks) |
| Info | infohash, save path, piece size/count, verified pieces, private flag, comment, creation date |
| Receipts | export a signed proof-of-download receipt for a torrent |
| Statistics | session rates/totals, DHT nodes, LSD counters, listen port, UPnP/NAT-PMP state, engine cache counters |
| Settings | save path, queue limits, connection (ports, DHT/PEX/LSD, encryption), speed limits, BitTorrent options, WebUI options and password |
| Search | the same multi-site search the desktop app uses, results → add |
| RSS | feed list, article list, magnet extraction → add |
| Create torrent | server-side directory walk (keeps the directory structure), name, piece size (auto ≈2000 pieces), announce tiers, `source`, `private` (BEP-27), comment, live progress, cancel, download or add-and-seed |

Everything the desktop UI writes to disk lands in the same files: settings,
resume data (`.fastresume`-style state), receipts and the torrent records — so a
NAS instance and a desktop instance are interchangeable (point them at the same
`--data` directory only if you know what you are doing; the engine is a
single-instance-per-process worker).

---

## 2. 飞牛 fnOS (native `.fpk` app)

fnOS packages are directories wrapped by `fnpack`:

```
packaging/fnos/typebittorrent/
├── manifest              # INI, no extension — appname/version/display_name/…
├── config/privilege      # {"defaults":{"run-as":"package"}}
├── config/resource       # shared folders
├── cmd/main              # start | stop | status  (exit 0 / 3 / 1)
├── app/ui/config         # App-Center entry (iframe → the WebUI port)
├── app/bin|lib|runtime   # payload: the self-contained Linux app image
├── wizard/               # (required directory)
└── ICON.PNG, ICON_256.PNG
```

Build it on a Linux x86_64 host:

```bash
# one-time: the official packaging tool
curl -fLO https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64
chmod +x fnpack-1.2.3-linux-amd64 && sudo mv fnpack-1.2.3-linux-amd64 /usr/local/bin/fnpack

sudo apt install python3-pil        # to render ICON.PNG / ICON_256.PNG
bash packaging/fnos/build-fpk.sh    # builds the payload, renders icons, runs fnpack build
```

Install on the NAS (per the fnOS CLI docs):

```bash
appcenter-cli install-fpk packaging/fnos/typebittorrent/typebittorrent.fpk
appcenter-cli start typebittorrent
```

Notes and honest caveats:

* **The package ships its own JRE.** fnOS supports `install_dep_apps=java-21-openjdk`,
  but bundling the runtime removes a whole class of "the NAS Java is too old"
  failures; the payload is ~150 MB larger.
* `manifest.service_port=8080` + `"port": "8080"` in `app/ui/config` make the
  App Center card open the WebUI in an iframe. The published WebUI port is the
  *service* port; change it in both files if 8080 is taken.
* `cmd/main` stops the client with `SIGTERM` so the resume data is flushed and
  the engine worker is joined (`status` follows the documented 0/3/1 contract).
* Publishing to the fnOS App Center currently goes through their developer
  group (there is no self-serve portal yet) — see
  <https://developer.fnnas.com/docs/quick-started/publish-application/>.
* `.fpk` is verified by `fnpack build` (manifest fields, JSON configs, icons,
  `app/`, `cmd/`, `wizard/`). The archive container itself is not publicly
  documented; `fnpack` is the supported way to produce it and is used verbatim
  here.

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
| WebUI | 8080/tcp | any free port |
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
Environment=TYPEBIT_PORT=8080
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
    reverse_proxy 127.0.0.1:8080
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
