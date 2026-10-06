#!/usr/bin/env bash
# build-fpk.sh — assemble the fnOS package and hand it to the official `fnpack`.
#
# Prerequisites (all documented at developer.fnnas.com):
#   * a Linux x86_64 host with JDK 17+, Rust 1.95+, git (to build the payload);
#   * `fnpack` — https://developer.fnnas.com/docs/cli/fnpack/
#        curl -fLO https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64
#        chmod +x fnpack-1.2.3-linux-amd64 && sudo mv fnpack-1.2.3-linux-amd64 /usr/local/bin/fnpack
#
# What it does:
#   1. scripts/build-linux.sh → the self-contained Linux app image (bundled JRE);
#   2. copies it into the package as `app/bin` + `app/lib`, keeping the fnOS
#      entry (`app/ui`) that the App Center card uses;
#   3. renders ICON.PNG / ICON_256.PNG (64×64 and 256×256, sRGB, ≤1 MB) from
#      assets/typebittorrent.png;
#   4. runs `fnpack build`, which validates manifest/config/icons and emits the
#      distributable `.fpk`.
#
# The package is intentionally NATIVE (no Docker): fnOS runs the payload
# directly under its own app account and manages start/stop/status through
# `cmd/main`, so the client works on fnOS boxes that have no container runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="$ROOT/packaging/fnos/typebittorrent"
APP_IMAGE="$ROOT/composeApp/build/compose/binaries/main/app/TypeBitTorrent"

command -v fnpack >/dev/null || { echo "fnpack not found — see the header of this script" >&2; exit 1; }

echo "==> building the Linux payload"
bash "$ROOT/scripts/build-linux.sh"
[ -x "$APP_IMAGE/bin/TypeBitTorrent" ] || { echo "missing app image: $APP_IMAGE" >&2; exit 1; }

echo "==> copying payload into the package"
rm -rf "$PKG/app/bin" "$PKG/app/lib"
mkdir -p "$PKG/app/bin"
# `app/` keeps ui/ (the App Center entry) and gains the runtime payload.
cp -a "$APP_IMAGE/bin/." "$PKG/app/bin/"
cp -a "$APP_IMAGE/lib" "$PKG/app/lib"
[ -d "$APP_IMAGE/runtime" ] && cp -a "$APP_IMAGE/runtime" "$PKG/app/runtime"
chmod 0755 "$PKG/cmd/main" "$PKG/app/bin/TypeBitTorrent"

echo "==> rendering icons"
ICON_SRC="$ROOT/assets/typebittorrent.png"
if [ -f "$ICON_SRC" ]; then
    python3 - "$ICON_SRC" "$PKG" <<'PY'
import sys
try:
    from PIL import Image
except ImportError:
    print("Pillow not installed — install python3-pil (apt install python3-pil) to render ICON.PNG")
    sys.exit(0)
src, pkg = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGBA")
for size, name in ((64, "ICON.PNG"), (256, "ICON_256.PNG")):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scaled = img.resize((size, size), Image.LANCZOS)
    canvas.alpha_composite(scaled)
    canvas.convert("RGB").save(f"{pkg}/{name}", format="PNG", optimize=True)
    print(f"    -> {pkg}/{name}")
PY
else
    echo "!! assets/typebittorrent.png missing — ICON.PNG / ICON_256.PNG must exist for fnpack" >&2
fi

for icon in ICON.PNG ICON_256.PNG; do
    [ -f "$PKG/$icon" ] || { echo "missing $PKG/$icon" >&2; exit 1; }
done

echo "==> fnpack build"
(cd "$PKG" && fnpack build)

echo "==> done. Install on the NAS with:"
echo "    appcenter-cli install-fpk $PKG/*.fpk"
