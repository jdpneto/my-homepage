#!/bin/bash
#
# Install or upgrade camera-uploader on the Mac mini. Idempotent.
#
#   sudo ./deploy/install.sh
#
# First install asks (silently) for the hub and WebDAV passwords, so run it in
# your own terminal: they never appear on screen, in shell history or in logs.
#
set -euo pipefail

LABEL=eu.davidneto.camera-uploader
RUN_USER=${CAMERA_UPLOADER_USER:-davidneto}
SRC="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX=/opt/camera-uploader
ETC=/usr/local/etc/camera-uploader
CONFIG=$ETC/config.toml
VAR=/usr/local/var/camera-uploader
LOGDIR=/usr/local/var/log/camera-uploader
PLIST=/Library/LaunchDaemons/$LABEL.plist
BREW=/opt/homebrew/bin/brew
PY=/opt/homebrew/bin/python3.13

say()  { printf '>>> %s\n' "$*"; }
warn() { printf '!!! %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }
as_user() { sudo -u "$RUN_USER" -H "$@"; }

[ "$(id -u)" = 0 ] || die "run with sudo: sudo $0"
id "$RUN_USER" >/dev/null 2>&1 || die "user $RUN_USER does not exist"
[ -x "$BREW" ] || die "Homebrew not found at $BREW"

say "Homebrew packages (python@3.13, ffmpeg) as $RUN_USER"
as_user "$BREW" list python@3.13 >/dev/null 2>&1 || as_user "$BREW" install python@3.13
as_user "$BREW" list ffmpeg >/dev/null 2>&1 || as_user "$BREW" install ffmpeg
[ -x "$PY" ] || die "$PY missing after brew install"
[ -x /opt/homebrew/bin/ffmpeg ] || die "ffmpeg missing after brew install"

say "Stopping the running service (if any) so only one uploader runs"
launchctl bootout "system/$LABEL" 2>/dev/null || true

say "Code -> $PREFIX"
mkdir -p "$PREFIX/src"
rsync -a --delete --exclude .venv --exclude __pycache__ --exclude '*.egg-info' \
      --exclude .pytest_cache "$SRC/" "$PREFIX/src/"
[ -x "$PREFIX/venv/bin/python" ] || "$PY" -m venv "$PREFIX/venv"
"$PREFIX/venv/bin/pip" install --quiet --upgrade pip
"$PREFIX/venv/bin/pip" install --quiet "$PREFIX/src"
chown -R root:wheel "$PREFIX"
chmod -R go-w "$PREFIX"

say "Directories"
install -d -o "$RUN_USER" -g staff -m 755 "$VAR" "$VAR/tmp" "$LOGDIR"
install -d -o root -g wheel -m 755 "$ETC"

if [ ! -f "$CONFIG" ]; then
    [ -t 0 ] || die "first install needs a terminal to ask for passwords: ssh -t ... sudo $0"
    say "Creating $CONFIG"
    read -r -s -p "Hub password (Reolink user admin): " HUB_PW; echo
    read -r -s -p "WebDAV password (user cameras): " DAV_PW; echo
    [ -n "$HUB_PW" ] && [ -n "$DAV_PW" ] || die "passwords must not be empty"
    umask 077
    HUB_PW="$HUB_PW" DAV_PW="$DAV_PW" TEMPLATE="$SRC/deploy/config.example.toml" \
        "$PREFIX/venv/bin/python" -c '
import os, sys
from camera_uploader.config import render_config
sys.stdout.write(render_config(open(os.environ["TEMPLATE"]).read(), os.environ["HUB_PW"], os.environ["DAV_PW"]))
' > "$CONFIG.new"
    unset HUB_PW DAV_PW
    mv "$CONFIG.new" "$CONFIG"
fi
chown "$RUN_USER":staff "$CONFIG"
chmod 600 "$CONFIG"

say "Unattended-operation checks"
fdesetup status | grep -q "FileVault is Off" \
    || warn "FileVault is on: after a power cut uploads pause until someone unlocks this Mac"
pmset -g | grep -qE '^ *sleep +0' || warn "system sleep is not 0: run 'sudo pmset -a sleep 0'"
pmset -g | grep -qE '^ *autorestart +1' || warn "autorestart is off: run 'sudo pmset -a autorestart 1'"

say "Test run (--once). The first run uploads up to 30 days of clips and can take several minutes."
if ! as_user "$PREFIX/venv/bin/camera-uploader" --config "$CONFIG" --once; then
    die "test run failed - see $LOGDIR/uploader.log; the service was NOT installed"
fi

say "Installing LaunchDaemon $LABEL"
install -o root -g wheel -m 644 "$SRC/deploy/$LABEL.plist" "$PLIST"
launchctl bootstrap system "$PLIST"
sleep 2
launchctl print "system/$LABEL" | grep -E '^\s*(state|pid) =' || true
say "Done. Log: $LOGDIR/uploader.log"
