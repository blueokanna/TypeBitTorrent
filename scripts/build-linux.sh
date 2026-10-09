#!/usr/bin/env bash
# build-linux.sh — build the Linux distribution (native engine + app image).
#
# This is the artifact the NAS targets consume:
#   * 飞牛 fnOS  → wrapped into a .fpk (packaging/fnos)
#   * Unraid     → baked into the container image (packaging/docker/Dockerfile)
#   * any Linux  → `make -C ...`/DESKTOP use; the app also runs with a window
#                  when a display is available.
#
# Requirements: JDK 17+, Rust stable 1.95+, git, curl, tar, and (for the app
# image) the usual jpackage prerequisites (`binutils`).
#
# Usage:
#   scripts/build-linux.sh                 # payload for the host architecture
#
# `jpackage` can only build for the machine it runs on, so cross-architecture
# payloads (the arm64 .fpk) are assembled by packaging/fnos/build-fpk.sh, which
# calls the same cargo/gradle steps plus a cross jlink.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE="$ROOT/native"
RES="$ROOT/composeApp/src/desktopMain/resources/native"
OUT="$ROOT/composeApp/build/compose/binaries/main/app/TypeBitTorrent"

echo "==> building native engine (release)"
(cd "$NATIVE" && cargo build --release)

LIB="$NATIVE/target/release/libtypebit_native.so"
[ -f "$LIB" ] || { echo "missing $LIB" >&2; exit 1; }
mkdir -p "$RES"
cp "$LIB" "$RES/libtypebit_native.so"
echo "    -> $RES/libtypebit_native.so"

echo "==> building desktop distribution (this also packages the runtime)"
(cd "$ROOT" && bash ./gradlew --console=plain :composeApp:createDistributable)

[ -x "$OUT/bin/TypeBitTorrent" ] || { echo "missing launcher: $OUT/bin/TypeBitTorrent" >&2; exit 1; }
echo "==> app image: $OUT"

# A tarball is what the .fpk / container builds copy around.
TARBALL="$ROOT/build-TypeBitTorrent-linux-$(uname -m).tar.gz"
tar -C "$(dirname "$OUT")" -czf "$TARBALL" "$(basename "$OUT")"
echo "==> tarball: $TARBALL"
echo
echo "Headless smoke test:"
echo "  $OUT/bin/TypeBitTorrent --headless --bind=127.0.0.1 --port=18881 \\"
echo "      --data=/tmp/typebit --downloads=/tmp/typebit/downloads --password=changeme"
