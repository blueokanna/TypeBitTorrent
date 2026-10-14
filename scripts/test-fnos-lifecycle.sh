#!/usr/bin/env bash
# Lifecycle regression test for the fnOS package (packaging/fnos/typebittorrent).
#
# Run it on any Linux host with bash, gcc and python3:
#
#     scripts/test-fnos-lifecycle.sh
#
# It stages the package into a temp "NAS" tree (TRIM_APPDEST, TRIM_PKGVAR,
# TRIM_PKGETC, TRIM_DATA_SHARE_PATHS, TRIM_DATA_ACCESSIBLE_PATHS), replaces the
# payload launcher with a stub that behaves like the real jpackage launcher
# (native executable, same flags, binds the WebUI port, exits on SIGTERM) and
# drives every lifecycle script the fnOS framework calls: install, config,
# start/stop/status/restart, upgrade and uninstall — plus the download-folder
# resolution and the App Center settings form.
set -u

REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
WORK="${WORK:-/tmp/fnos-lifecycle-test}"
ASSETS="${ASSETS:-/tmp/fnos-lifecycle-assets}"
PORT="${PORT:-18991}"

PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
section() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# assert <description> <command...>
assert() {
    local desc="$1"
    shift
    if "$@" >/dev/null 2>&1; then ok "$desc"; else bad "$desc"; fi
}

PKG="$WORK/approot"
DATA="$WORK/data"
ETC="$WORK/etc"
SHARE="$WORK/shares/typebittorrent/downloads"
AUTH="$WORK/authorised"
MOVIES="$WORK/movies"

launcher_pids() {
    local d pid exe
    for d in /proc/[0-9]*; do
        pid="${d#/proc/}"
        [ -r "$d/cmdline" ] || continue
        exe="$(tr '\0' '\n' <"$d/cmdline" 2>/dev/null | head -n 1)"
        case "$exe" in "$PKG/target/bin/TypeBitTorrent") printf '%s\n' "$pid" ;; esac
    done
}

kill_launchers() {
    local pids
    pids="$(launcher_pids)"
    [ -n "$pids" ] && kill $pids 2>/dev/null
    sleep 0.3
    return 0
}

build_stub() {
    mkdir -p "$ASSETS"
    cat >"$ASSETS/stub.c" <<'EOF'
/* Stub of the jpackage launcher: same argv[0] shape, records its arguments,
 * binds the WebUI port (exit 2 when it cannot) and exits on SIGTERM. */
#include <arpa/inet.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *port = NULL, *data = NULL, *downloads = NULL, *force = NULL;
    for (int i = 1; i < argc; i++) {
        if (!strncmp(argv[i], "--port=", 7)) port = argv[i] + 7;
        else if (!strncmp(argv[i], "--data=", 7)) data = argv[i] + 7;
        else if (!strncmp(argv[i], "--downloads-force=", 18)) force = argv[i] + 18;
        else if (!strncmp(argv[i], "--downloads=", 12)) downloads = argv[i] + 12;
    }
    if (data) {
        char path[4096];
        snprintf(path, sizeof path, "%s/last-start.txt", data);
        FILE *f = fopen(path, "w");
        if (f) {
            const char *pw = getenv("TYPEBIT_PASSWORD");
            fprintf(f, "port=%s\ndownloads=%s\nforce=%s\npassword=%s\n",
                    port ? port : "", downloads ? downloads : "", force ? force : "",
                    pw ? pw : "");
            fclose(f);
        }
    }
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((unsigned short)atoi(port ? port : "0"));
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if (bind(s, (struct sockaddr *)&a, sizeof a) != 0) return 2;
    listen(s, 8);
    for (;;) pause();
    return 0;
}
EOF
    gcc -O1 -o "$ASSETS/TypeBitTorrent" "$ASSETS/stub.c"
}

reset_env() {
    kill_launchers
    rm -rf "$WORK"
    mkdir -p "$PKG" "$DATA" "$ETC" "$WORK/tmp" "$SHARE" "$AUTH" "$MOVIES"
    cp -a "$REPO/packaging/fnos/typebittorrent/." "$PKG/"
    # A Windows checkout may carry CRLF; fnpack normalises it at package time,
    # so do the same here before running the scripts.
    find "$PKG/cmd" "$PKG/wizard" -type f -exec sed -i 's/\r$//' {} +
    sed -i 's/\r$//' "$PKG/manifest" "$PKG/config/privilege" "$PKG/config/resource" "$PKG/app/ui/config"
    mkdir -p "$PKG/target/bin"
    cp "$ASSETS/TypeBitTorrent" "$PKG/target/bin/TypeBitTorrent"
    chmod 0755 "$PKG/target/bin/TypeBitTorrent" "$PKG/cmd/"*

    export TRIM_APPDEST="$PKG/target" TRIM_PKGVAR="$DATA" TRIM_PKGETC="$ETC"
    export TRIM_PKGTMP="$WORK/tmp" TRIM_SERVICE_PORT="$PORT"
    export TRIM_TEMP_LOGFILE="$WORK/user-error.txt"
    export TRIM_DATA_SHARE_PATHS="$SHARE"
    unset TRIM_DATA_ACCESSIBLE_PATHS wizard_download_dir wizard_webui_password wizard_upgrade_start
}

main_cmd() { bash "$PKG/cmd/main" "$@"; }
stage() { bash "$PKG/cmd/$1" "${@:2}"; }

build_stub

section "shell syntax"
reset_env
for f in "$PKG"/cmd/*; do
    assert "bash -n cmd/$(basename "$f")" bash -n "$f"
done
for w in install upgrade uninstall config; do
    assert "wizard/$w is valid JSON" python3 -m json.tool "$PKG/wizard/$w"
done
assert "manifest declares the authorised-directory setting" grep -q '^disable_authorization_path=false$' "$PKG/manifest"

section "install: wizard password + settings file"
reset_env
wizard_webui_password='SuperSecret123' stage install_callback
assert "install_callback stores the wizard password" test -s "$DATA/initial-password.txt"
assert "install_callback creates typebit.conf" test -f "$ETC/typebit.conf"
assert "the settings file leaves download_dir automatic" bash -c "! grep -q '^download_dir=' '$ETC/typebit.conf'"
assert "the password file is private" bash -c "test \$(stat -c %a '$DATA/initial-password.txt') = 600"

section "first start: share folder, wizard password applied once"
reset_env
wizard_webui_password='SuperSecret123' stage install_callback
main_cmd start
st=$?
assert "start returns 0" test "$st" = 0
assert "the launcher is listening on the packaged port" bash -c "exec 3<>/dev/tcp/127.0.0.1/$PORT"
assert "downloads = the declared share" grep -q "^downloads=$SHARE\$" "$DATA/last-start.txt"
assert "downloads forced on the first start" grep -q "^force=$SHARE\$" "$DATA/last-start.txt"
assert "the wizard password reached the client" grep -q '^password=SuperSecret123$' "$DATA/last-start.txt"
assert "password marker written" test -f "$DATA/.pw-applied"
assert "download marker written" test -f "$DATA/.downloads-applied"
main_cmd status
st=$?
assert "status = 0 while running" test "$st" = 0
main_cmd stop
main_cmd status
st=$?
assert "status = 3 after stop" test "$st" = 3
sleep 0.3
assert "no launcher process is left" test -z "$(launcher_pids)"

section "authorised directory wins over the share when the form is empty"
reset_env
TRIM_DATA_ACCESSIBLE_PATHS="$AUTH" main_cmd start
st=$?
assert "start returns 0" test "$st" = 0
assert "downloads = the authorised directory" grep -q "^downloads=$AUTH\$" "$DATA/last-start.txt"
main_cmd stop

section "config form: validation"
reset_env
wizard_download_dir='relative/path' stage config_init
st=$?
assert "relative paths are rejected" test "$st" = 1
assert "the user sees why" grep -q "绝对路径" "$WORK/user-error.txt"
wizard_download_dir='/tmp/x/../etc' stage config_init
st=$?
assert "'..' is rejected" test "$st" = 1
wizard_webui_password='short' stage config_init
st=$?
assert "short passwords are rejected" test "$st" = 1
wizard_download_dir="$MOVIES" wizard_webui_password='BrandNewPass9' stage config_init
st=$?
assert "valid input passes" test "$st" = 0

section "config form: saved while stopped, applied by the next start"
reset_env
wizard_download_dir="$MOVIES" wizard_webui_password='BrandNewPass9' stage config_callback
st=$?
assert "config_callback returns 0" test "$st" = 0
assert "typebit.conf records the folder" grep -q "^download_dir=$MOVIES\$" "$ETC/typebit.conf"
assert "the new password is staged" grep -q 'BrandNewPass9' "$DATA/initial-password.txt"
assert "the applied marker was cleared" bash -c "! test -f '$DATA/.pw-applied'"
main_cmd start
assert "downloads = the configured folder" grep -q "^downloads=$MOVIES\$" "$DATA/last-start.txt"
assert "the configured folder is forced" grep -q "^force=$MOVIES\$" "$DATA/last-start.txt"
assert "the new password reached the client" grep -q '^password=BrandNewPass9$' "$DATA/last-start.txt"
main_cmd stop
rm -f "$DATA/last-start.txt"
main_cmd start
assert "an unchanged folder is not forced again (WebUI keeps its save path)" grep -q '^force=$' "$DATA/last-start.txt"
main_cmd stop

section "config change while running restarts the service"
reset_env
main_cmd start
pid1="$(cat "$DATA/typebit.pid")"
wizard_download_dir="$AUTH" stage config_callback
st=$?
assert "config_callback returns 0" test "$st" = 0
assert "a new process took over" test "$pid1" != "$(cat "$DATA/typebit.pid" 2>/dev/null || echo none)"
main_cmd status
st=$?
assert "the service is running again" test "$st" = 0
assert "the new folder is in effect" grep -q "^force=$AUTH\$" "$DATA/last-start.txt"
main_cmd stop

section "orphans, adoption and unusable folders"
reset_env
main_cmd start
rm -f "$DATA/typebit.pid"
main_cmd status
st=$?
assert "status adopts a live instance" test "$st" = 0
assert "the pid file is back" test -f "$DATA/typebit.pid"
main_cmd stop
st=$?
assert "stop returns 0" test "$st" = 0
sleep 0.3
assert "no launcher process is left" test -z "$(launcher_pids)"

mkdir -p "$WORK/readonly"
chmod 0555 "$WORK/readonly"
printf 'download_dir=%s\n' "$WORK/readonly" >"$ETC/typebit.conf"
main_cmd start
st=$?
assert "start refuses an unwritable download folder" test "$st" = 1
assert "the App Center shows the reason" grep -q "下载目录不可写" "$WORK/user-error.txt"
chmod 0755 "$WORK/readonly"

section "upgrade: state restored, opt-out honoured"
reset_env
main_cmd start
stage upgrade_init
main_cmd status
st=$?
assert "upgrade_init stops the service" test "$st" = 3
assert "the previous state is remembered" test -f "$DATA/.was-running"
stage upgrade_callback
main_cmd status
st=$?
assert "upgrade_callback starts it again" test "$st" = 0
assert "the marker is cleared" bash -c "! test -f '$DATA/.was-running'"
assert "the version change is logged" grep -q 'upgrade:' "$DATA/typebit.log"
main_cmd stop

main_cmd start
stage upgrade_init
wizard_upgrade_start=false stage upgrade_callback
main_cmd status
st=$?
assert "wizard_upgrade_start=false leaves it stopped" test "$st" = 3
main_cmd stop

section "uninstall: service stopped first"
reset_env
main_cmd start
stage uninstall_init
main_cmd status
st=$?
assert "uninstall_init stops the client" test "$st" = 3
assert "the pid file is gone" bash -c "! test -f '$DATA/typebit.pid'"
sleep 0.3
assert "no launcher process is left" test -z "$(launcher_pids)"

section "log rotation"
reset_env
python3 -c "open('$DATA/typebit.log','w').write('x' * 6000000)"
TYPEBIT_LOG_MAX_BYTES=1000 main_cmd start
assert "the old log is kept as .1" test -s "$DATA/typebit.log.1"
assert "the new log is small" bash -c "test \$(wc -c <'$DATA/typebit.log') -lt 1000"
main_cmd stop

kill_launchers
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
