#!/usr/bin/env bash
# build-fpk.sh — assemble the 飞牛 fnOS app package (.fpk) for x86_64 and arm64.
#
# Usage:
#   packaging/fnos/build-fpk.sh [x86_64|aarch64|all]        (default: all)
#
# Requirements
#   * a Linux host, JDK 17 in JAVA_HOME, Rust, git, curl, tar, python3;
#   * `fnpack` — https://developer.fnnas.com/docs/cli/fnpack/
#       curl -fLO https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64
#       chmod +x fnpack-1.2.3-linux-amd64 && sudo mv fnpack-1.2.3-linux-amd64 /usr/local/bin/fnpack
#   * for `aarch64`:
#       - an aarch64 JDK 17 of the same release (ARM_JDK, default ~/jdks/arm64):
#           curl -fL -o jdk-arm64.tar.gz \
#             'https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse'
#       - a cross linker: `sudo apt install gcc-aarch64-linux-gnu`, or point
#         CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER at your own.
#
# What it produces
#   packaging/fnos/dist/typebittorrent_<version>_x86.fpk
#   packaging/fnos/dist/typebittorrent_<version>_arm.fpk
#
# Why the arm64 payload is assembled by hand: `jpackage` cannot cross-build an
# app image, so the arm64 image is rebuilt to match what
# `:composeApp:createDistributable` emits on x86_64 — the same jpackage
# launcher, the same `bin/` + `lib/app` + `lib/runtime` split, the same `.cfg` —
# with the launcher and the runtime taken from the aarch64 JDK, the native
# engine from a cross build and skiko's arm64 native instead of the x64 one.
#
# Architecture is declared to fnOS through `manifest`'s `platform=` (`x86` /
# `arm`): `fnpack` has no arch flag, and the App Center expects one .fpk per
# architecture — which is exactly what dist/ ends up holding.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PKG="$ROOT/packaging/fnos/typebittorrent"
BUILD="$ROOT/packaging/fnos/build"
DIST="$ROOT/packaging/fnos/dist"
IMAGE="$ROOT/composeApp/build/compose/binaries/main/app/TypeBitTorrent"
ARM_JDK="${ARM_JDK:-$HOME/jdks/arm64}"
GRADLE="$ROOT/gradlew"

# skiko ships the platform part of Compose's renderer. The x64 copy comes from
# the local build; the arm64 one is fetched from Maven Central.
SKIKO_VERSION=0.9.4.2
SKIKO_ARM_URL="https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-linux-arm64/$SKIKO_VERSION/skiko-awt-runtime-linux-arm64-$SKIKO_VERSION.jar"

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31m!!\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

# ---------------------------------------------------------------------- native

build_native_x86() {
    log "native engine (x86_64-unknown-linux-gnu)"
    ( cd "$ROOT/native" && cargo build --release )
    cp "$ROOT/native/target/release/libtypebit_native.so" \
       "$ROOT/composeApp/src/desktopMain/resources/native/libtypebit_native.so"
}

build_native_arm() {
    log "native engine (aarch64-unknown-linux-gnu, cross)"
    ( cd "$ROOT/native" && \
      CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER="${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER:-aarch64-linux-gnu-gcc}" \
      cargo build --release --target aarch64-unknown-linux-gnu )
    cp "$ROOT/native/target/aarch64-unknown-linux-gnu/release/libtypebit_native.so" \
       "$ROOT/composeApp/src/desktopMain/resources/native/libtypebit_native.so"
}

# ------------------------------------------------------------------- app images

# The x86_64 image is what jpackage produces for the host; every later step
# reuses its jars, its cfg and its icon.
build_image_x86() {
    build_native_x86
    log "jpackage app image (host = x86_64)"
    ( cd "$ROOT" && "$GRADLE" --console=plain :composeApp:createDistributable )
    [ -x "$IMAGE/bin/TypeBitTorrent" ] || die "missing $IMAGE/bin/TypeBitTorrent"
}

# Pulls the launcher template and the launcher's shared library out of an
# aarch64 JDK. A jmod is a zip with a four-byte prefix, so python's zipfile can
# read it even on an x86_64 host (the aarch64 binaries themselves never run).
extract_arm_launcher() {
    local jdk="$1" dest="$2"
    local jmod="$jdk/jmods/jdk.jpackage.jmod"
    [ -f "$jmod" ] || die "no jdk.jpackage module in $jdk (a full JDK is required)"
    python3 - "$jmod" "$dest" <<'PY'
import os, shutil, sys, zipfile
jmod, dest = sys.argv[1], sys.argv[2]
base = "classes/jdk/jpackage/internal/resources/"
want = {
    "jpackageapplauncher": ("bin/TypeBitTorrent", 0o755),
    "libjpackageapplauncheraux.so": ("lib/libapplauncher.so", 0o755),
}
with zipfile.ZipFile(jmod) as z:
    for name, (target, mode) in want.items():
        out = os.path.join(dest, target)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with z.open(base + name) as src, open(out, "wb") as dst:
            shutil.copyfileobj(src, dst)
        os.chmod(out, mode)
        print(f"    {target} ({os.path.getsize(out)} bytes)")
PY
}

# `jlink` builds the aarch64 runtime from the aarch64 jmods; the module set is
# read from the x86_64 image so both packages ship the same JRE.
build_arm_runtime() {
    local out="$1" modules
    modules="$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$IMAGE/lib/runtime/release" | tr ' ' ',')"
    [ -n "$modules" ] || die "cannot read the module list from $IMAGE/lib/runtime/release"
    log "jlink aarch64 runtime ($modules)"
    rm -rf "$out"
    if "$JAVA_HOME/bin/jlink" \
        --module-path "$ARM_JDK/jmods" \
        --add-modules "$modules" \
        --output "$out" \
        --strip-native-commands --strip-debug --no-header-files --no-man-pages 2>"$BUILD/jlink.log"; then
        # Same shape as jpackage's own runtime: no bin/java, the launcher drives
        # the JVM through lib/server/libjvm.so.
        [ -f "$out/lib/server/libjvm.so" ] || die "jlink produced a runtime without libjvm.so"
        return
    fi
    # A JDK whose jmods are missing still has a usable runtime image, so ship it
    # whole instead of failing (larger, but complete).
    echo "   (cross-jlink failed, using the full aarch64 runtime — see $BUILD/jlink.log)"
    mkdir -p "$out"
    cp -a "$ARM_JDK/bin" "$ARM_JDK/lib" "$ARM_JDK/conf" "$out/"
    cp -a "$ARM_JDK/release" "$out/" 2>/dev/null || true
}

# Rebuilds `lib/app` for arm64: the app jar now carries the aarch64 engine, and
# skiko's arm64 native replaces the x64 one.
build_arm_app_dir() {
    local out="$1"
    build_native_arm
    log "re-jarring the app classes with the aarch64 engine"
    ( cd "$ROOT" && "$GRADLE" --console=plain :composeApp:desktopJar )
    local jar
    jar="$(ls -t "$ROOT"/composeApp/build/libs/composeApp-desktop*.jar | head -1)"
    [ -n "$jar" ] || die ":composeApp:desktopJar produced no jar"

    rm -rf "$out"
    cp -a "$IMAGE/lib/app" "$out"
    local cfg="$out/TypeBitTorrent.cfg"
    local old_app_jar old_skiko
    old_app_jar="$(ls "$out"/composeApp-desktop*.jar)"
    cp -f "$jar" "$out/composeApp-desktop.jar"
    rm -f "$old_app_jar"
    sed -i "s#app.classpath=\$APPDIR/$(basename "$old_app_jar")#app.classpath=\$APPDIR/composeApp-desktop.jar#" "$cfg"
    grep -q 'app.classpath=\$APPDIR/composeApp-desktop.jar' "$cfg" ||
        die "could not patch the classpath in $cfg"

    log "swapping skiko's x64 native for the arm64 build"
    rm -f "$out"/libskiko-linux-x64.so "$out"/libskiko-linux-x64.so.sha256
    old_skiko="$(ls "$out"/skiko-awt-runtime-linux-x64-*.jar)"
    python3 - "$SKIKO_ARM_URL" "$out" <<'PY'
import os, shutil, sys, urllib.request, zipfile
url, out = sys.argv[1], sys.argv[2]
jar = os.path.join(out, os.path.basename(url))
if not os.path.exists(jar):
    print(f"    downloading {os.path.basename(url)}")
    with urllib.request.urlopen(url) as r, open(jar, "wb") as f:
        shutil.copyfileobj(r, f)
with zipfile.ZipFile(jar) as z:
    for name in ("libskiko-linux-arm64.so", "libskiko-linux-arm64.so.sha256"):
        with z.open(name) as src, open(os.path.join(out, name), "wb") as dst:
            shutil.copyfileobj(src, dst)
        print(f"    {name}")
    # Leave an empty stub jar, mirroring what Compose produces on x86_64: the
    # native lives next to the app and is found via -Dskiko.library.path.
    with open(jar, "wb") as f, zipfile.ZipFile(f, "w") as stub:
        stub.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
PY
    chmod 0755 "$out/libskiko-linux-arm64.so"
    sed -i "s#app.classpath=\$APPDIR/$(basename "$old_skiko")#app.classpath=\$APPDIR/$(basename "$SKIKO_ARM_URL")#" "$cfg"
    grep -q "$(basename "$SKIKO_ARM_URL")" "$cfg" || die "could not patch skiko in $cfg"
}

assemble_image_arm() {
    local dest="$1"
    log "assembling the aarch64 app image under $dest"
    [ -x "$ARM_JDK/bin/java" ] ||
        die "ARM_JDK=$ARM_JDK is not an aarch64 JDK (see the download command in the header)"
    case "$(file -b "$ARM_JDK/bin/java")" in
        *aarch64*|*ARM\ aarch64*) ;;
        *) die "$ARM_JDK/bin/java is not aarch64: $(file -b "$ARM_JDK/bin/java")" ;;
    esac
    rm -rf "$dest"
    mkdir -p "$dest/bin" "$dest/lib"
    extract_arm_launcher "$ARM_JDK" "$dest"
    cp -f "$IMAGE/lib/TypeBitTorrent.png" "$dest/lib/TypeBitTorrent.png"
    build_arm_runtime "$dest/lib/runtime"
    build_arm_app_dir "$dest/lib/app"
}

# ---------------------------------------------------------------------- package

# The App Center icons are committed, so this only refreshes them when one of
# them is missing (assets/typebittorrent.png is the source).
render_icons() {
    if [ -f "$PKG/ICON.PNG" ] && [ -f "$PKG/ICON_256.PNG" ] &&
        [ -f "$PKG/app/ui/images/icon_64.png" ] && [ -f "$PKG/app/ui/images/icon_256.png" ]; then
        return 0
    fi
    log "icons missing — rendering from assets/typebittorrent.png"
    python3 - "$ROOT/assets/typebittorrent.png" "$PKG" <<'PY' || true
import sys
try:
    from PIL import Image
except ImportError:
    sys.exit(0)
src, pkg = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGBA")
for size, path in ((64, f"{pkg}/ICON.PNG"), (256, f"{pkg}/ICON_256.PNG"),
                   (64, f"{pkg}/app/ui/images/icon_64.png"),
                   (256, f"{pkg}/app/ui/images/icon_256.png")):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(img.resize((size, size), Image.LANCZOS))
    canvas.convert("RGB").save(path, format="PNG", optimize=True)
PY
    for f in "$PKG/ICON.PNG" "$PKG/ICON_256.PNG" \
             "$PKG/app/ui/images/icon_64.png" "$PKG/app/ui/images/icon_256.png"; do
        [ -f "$f" ] || die "missing $f — render it from assets/typebittorrent.png"
    done
}

# Stages manifest/cmd/config/wizard/app + the payload, then hands it to fnpack.
# `platform` is the only metadata difference between the two packages (besides
# the payload itself), which is why fnpack runs on a copy instead of in-tree.
build_fpk() {
    local arch="$1" platform="$2" image="$3"
    local stage="$BUILD/$arch"
    log "staging the $platform package in $stage"
    rm -rf "$stage"
    mkdir -p "$stage/pkg/app"
    cp -a "$PKG/manifest" "$PKG/cmd" "$PKG/config" "$PKG/wizard" "$stage/pkg/"
    cp -a "$PKG/app/ui" "$stage/pkg/app/ui"
    cp -a "$PKG/ICON.PNG" "$PKG/ICON_256.PNG" "$stage/pkg/"
    cp -a "$image/bin" "$stage/pkg/app/bin"
    cp -a "$image/lib" "$stage/pkg/app/lib"
    sed -i "s/^platform=.*/platform=$platform/" "$stage/pkg/manifest"

    # fnOS runs cmd/* through /bin/bash and parses manifest/config as text, so a
    # checkout that carried CRLF (Windows + core.autocrlf) has to be normalised
    # here; the binaries under app/ are left untouched.
    local f
    for f in "$stage/pkg/manifest" "$stage/pkg/config/privilege" "$stage/pkg/config/resource" \
             "$stage/pkg/app/ui/config" "$stage/pkg"/cmd/* "$stage/pkg"/wizard/*; do
        [ -f "$f" ] && sed -i 's/\r$//' "$f"
    done
    chmod 0755 "$stage/pkg/cmd/"* "$stage/pkg/app/bin/TypeBitTorrent"

    log "fnpack build"
    ( cd "$stage/pkg" && fnpack build )
    [ -f "$stage/pkg/typebittorrent.fpk" ] || die "fnpack produced no .fpk"
    mkdir -p "$DIST"
    local version out
    version="$(sed -n 's/^version=//p' "$stage/pkg/manifest" | head -1)"
    out="$DIST/typebittorrent_${version}_${platform}.fpk"
    mv "$stage/pkg/typebittorrent.fpk" "$out"
    log "wrote $out ($(du -h "$out" | cut -f1))"
}

# ------------------------------------------------------------------------- main

target="${1:-all}"
case "$target" in
    x86_64|amd64) archs="x86_64" ;;
    aarch64|arm64) archs="aarch64" ;;
    all) archs="x86_64 aarch64" ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown target '$target' (expected x86_64, aarch64 or all)" ;;
esac

need fnpack
need python3
need cargo
need file
{ [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ]; } ||
    die "JAVA_HOME must point at a JDK 17 (jpackage not found)"
mkdir -p "$BUILD" "$DIST"
render_icons

for arch in $archs; do
    case "$arch" in
        x86_64)
            build_image_x86
            build_fpk x86_64 x86 "$IMAGE"
            ;;
        aarch64)
            assemble_image_arm "$BUILD/image-aarch64"
            build_fpk aarch64 arm "$BUILD/image-aarch64"
            ;;
    esac
done

log "done:"
ls -lh "$DIST"
echo
echo "Install on the NAS:"
echo "    appcenter-cli install-fpk $DIST/typebittorrent_<version>_x86.fpk   # x86_64 devices"
echo "    appcenter-cli install-fpk $DIST/typebittorrent_<version>_arm.fpk   # arm64 devices"
