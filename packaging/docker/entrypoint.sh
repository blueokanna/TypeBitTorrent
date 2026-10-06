#!/bin/sh
# Container entrypoint for TypeBitTorrent.
#
# Responsibilities, in order:
#   1. apply PUID/PGID (Unraid's convention) so files land on the array with
#      the user's ownership instead of root's;
#   2. make sure the two volume mounts exist and are writable;
#   3. exec the headless client — `exec` matters, the JVM must be PID 1 so
#      Docker's SIGTERM reaches the app and its shutdown hook can flush the
#      resume data and join the engine worker.
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
DATA="${TYPEBIT_DATA:-/config}"
DOWNLOADS="${TYPEBIT_DOWNLOADS:-/downloads}"

if [ "$(id -u)" = "0" ]; then
    # Re-point the service account at the requested ids (idempotent).
    if [ "$(id -g typebit 2>/dev/null || echo -1)" != "$PGID" ]; then
        groupmod -o -g "$PGID" typebit 2>/dev/null || groupadd -o -g "$PGID" typebit
    fi
    if [ "$(id -u typebit 2>/dev/null || echo -1)" != "$PUID" ]; then
        usermod -o -u "$PUID" -g "$PGID" typebit 2>/dev/null || true
    fi

    mkdir -p "$DATA" "$DOWNLOADS"
    # Only the mount roots are chowned; the array may hold huge media trees
    # and a recursive chown on every start would be a disaster.
    chown "$PUID:$PGID" "$DATA" "$DOWNLOADS" 2>/dev/null || true

    exec gosu "$PUID:$PGID" /opt/typebit/bin/TypeBitTorrent --headless "$@"
fi

exec /opt/typebit/bin/TypeBitTorrent --headless "$@"
