# TypeBitTorrent — container image for 飞牛 fnOS, Unraid and any Linux host.
#
# Build (from the repository root, so the whole source tree is the context):
#   docker build -f packaging/docker/Dockerfile -t typebittorrent:latest .
#
# The image runs the client in HEADLESS mode (no display anywhere) and serves
# the built-in WebUI on 18881 (8080 is usually already taken on a NAS); the
# BitTorrent port (6881 TCP+UDP) is published so peers can connect directly.
# Both directories the engine touches are volumes, so the container itself is
# disposable.

# --------------------------------------------------------------------- build
FROM eclipse-temurin:17-jdk-jammy AS build

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential pkg-config libssl-dev curl ca-certificates file binutils fakeroot \
    && rm -rf /var/lib/apt/lists/*

# Rust (stable) — the engine is a cdylib loaded by the JVM.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
        | sh -s -- -y --profile minimal --default-toolchain stable
ENV PATH="/root/.cargo/bin:${PATH}" \
    CARGO_TERM_COLOR=never

WORKDIR /src
COPY . .
# Builds native/release/libtypebit_native.so, deploys it into the desktop
# resources and produces the self-contained app image (bundled runtime).
RUN bash scripts/build-linux.sh

# ------------------------------------------------------------------- runtime
FROM debian:bookworm-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates tzdata curl gosu libfreetype6 fontconfig \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 typebit \
    && useradd -u 1000 -g 1000 -M -d /config -s /usr/sbin/nologin typebit \
    && mkdir -p /config /downloads && chown -R 1000:1000 /config /downloads

COPY --from=build /src/composeApp/build/compose/binaries/main/app/TypeBitTorrent /opt/typebit
COPY packaging/docker/entrypoint.sh /usr/local/bin/typebit-entrypoint
RUN chmod 0755 /usr/local/bin/typebit-entrypoint

# Container-friendly defaults; override with -e / Unraid template fields.
ENV TYPEBIT_DATA=/config \
    TYPEBIT_DOWNLOADS=/downloads \
    TYPEBIT_BIND=0.0.0.0 \
    TYPEBIT_PORT=18881 \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -XX:MaxRAMPercentage=50"

VOLUME ["/config", "/downloads"]
# 18881 = WebUI, 6881 = BitTorrent peer port (TCP and UDP).
EXPOSE 18881/tcp 6881/tcp 6881/udp

HEALTHCHECK --interval=60s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${TYPEBIT_PORT}/api/session" >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/typebit-entrypoint"]
