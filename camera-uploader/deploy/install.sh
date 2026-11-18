#!/bin/bash
#
# Install or upgrade camera-uploader on the Mac mini. Idempotent.
#
#   sudo ./deploy/install.sh                 # install / upgrade
#   sudo ./deploy/install.sh --reconfigure   # also ask for the passwords again
#
# Passwords are asked silently (twice), so run it in your own terminal: they
# never appear on screen, in shell history, in argv or in logs.
#
# Each install goes into /opt/camera-uploader/releases/<timestamp> and the
# `current` symlink is switched only after a successful test run. If anything
# fails after the running service was stopped, the previous version is started
# again before the script exits.
#
set -euo pipefail

LABEL=eu.davidneto.camera-uploader
RUN_USER=davidneto   # must match UserName in the plist
SRC="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX=/opt/camera-uploader
RELEASE=$PREFIX/releases/$(date +%Y%m%d%H%M%S)
CURRENT=$PREFIX/current
ETC=/usr/local/etc/camera-uploader
CONFIG=$ETC/config.toml
VAR=/usr/local/var/camera-uploader
LOGDIR=/usr/local/var/log/camera-uploader
PLIST=/Library/LaunchDaemons/$LABEL.plist
BREW=/opt/homebrew/bin/brew
PY=/opt/homebrew/bin/python3.13

RECONFIGURE=no
[ "${1:-}" = "--reconfigure" ] && RECONFIGURE=yes

say()  { printf '>>> %s\n' "$*"; }
warn() { printf '!!! %s\n' "$*" >&2; }
die()  { warn "$*"; exit 1; }
as_user() { sudo -u "$RUN_USER" -H "$@"; }

STOPPED=no
DONE=no
NEW_CONFIG=""
on_exit() {
    [ -n "$NEW_CONFIG" ] && rm -f "$NEW_CONFIG"
    if [ "$DONE" != yes ] && [ "$STOPPED" = yes ] && [ -f "$PLIST" ]; then
        warn "install did not finish - restarting the previous version"
        launchctl bootstrap system "$PLIST" 2>/dev/null || warn "could not restart $LABEL"
    fi
}
trap on_exit EXIT

ask_password() {  # $1 = prompt; prints the password on stdout
    local a b
    while :; do
        IFS= read -r -s -p "$1: " a; echo >&2
        IFS= read -r -s -p "$1 (again): " b; echo >&2
        if [ -z "$a" ]; then warn "must not be empty"; continue; fi
        if [ "$a" != "$b" ]; then warn "the two entries differ - try again"; continue; fi
        printf '%s' "$a"
        return
    done
}

[ "$(id -u)" = 0 ] || die "run with sudo: sudo $0"
id "$RUN_USER" >/dev/null 2>&1 || die "user $RUN_USER does not exist"
[ -x "$BREW" ] || die "Homebrew not found at $BREW"
if ! as_user "$BREW" --version >/dev/null 2>&1 || ! as_user "$BREW" config >/dev/null 2>&1; then
    die "Homebrew at $BREW does not work on this macOS (too old, or Command Line Tools/git missing).
    Fix: install the Command Line Tools (sudo softwareupdate -i \"Command Line Tools for Xcode ...\"),
    then run: $BREW update"
fi

say "Homebrew packages (python@3.13, ffmpeg) as $RUN_USER"
as_user "$BREW" list python@3.13 >/dev/null 2>&1 || as_user "$BREW" install python@3.13
as_user "$BREW" list ffmpeg >/dev/null 2>&1 || as_user "$BREW" install ffmpeg
[ -x "$PY" ] || die "$PY missing after brew install"
[ -x /opt/homebrew/bin/ffmpeg ] || die "ffmpeg missing after brew install"

say "Building release $RELEASE (the running service keeps going meanwhile)"
mkdir -p "$RELEASE"
rsync -a --exclude .venv --exclude __pycache__ --exclude '*.egg-info' --exclude .pytest_cache \
      "$SRC/" "$RELEASE/src/"
"$PY" -m venv "$RELEASE/venv"
"$RELEASE/venv/bin/pip" install --quiet --upgrade pip
"$RELEASE/venv/bin/pip" install --quiet "$RELEASE/src"
chown -R root:wheel "$RELEASE"
chmod -R go-w "$RELEASE"

say "Directories"
install -d -o "$RUN_USER" -g staff -m 755 "$VAR" "$VAR/tmp" "$LOGDIR"
install -d -o root -g wheel -m 755 "$ETC"

TEST_CONFIG=$CONFIG
if [ ! -f "$CONFIG" ] || [ "$RECONFIGURE" = yes ]; then
    [ -t 0 ] || die "asking for passwords needs a terminal: ssh -t ... sudo $0"
    say "Passwords for $CONFIG"
    HUB_PW=$(ask_password "Hub password (Reolink user admin)")
    DAV_PW=$(ask_password "WebDAV password (user cameras)")
    NEW_CONFIG=$CONFIG.new
    ( umask 077
      HUB_PW="$HUB_PW" DAV_PW="$DAV_PW" TEMPLATE="$SRC/deploy/config.example.toml" \
          "$RELEASE/venv/bin/python" -c '
import os, sys
from camera_uploader.config import render_config
sys.stdout.write(render_config(open(os.environ["TEMPLATE"]).read(), os.environ["HUB_PW"], os.environ["DAV_PW"]))
' > "$NEW_CONFIG" )
    unset HUB_PW DAV_PW
    chown "$RUN_USER":staff "$NEW_CONFIG"
    chmod 600 "$NEW_CONFIG"
    TEST_CONFIG=$NEW_CONFIG
fi

say "Unattended-operation checks"
fdesetup status | grep -q "FileVault is Off" \
    || warn "FileVault is on: after a power cut uploads pause until someone unlocks this Mac"
pmset -g | grep -qE '^ *sleep +0' || warn "system sleep is not 0: run 'sudo pmset -a sleep 0'"
pmset -g | grep -qE '^ *autorestart +1' || warn "autorestart is off: run 'sudo pmset -a autorestart 1'"

say "Stopping the running service (if any) so only one uploader runs"
if launchctl print "system/$LABEL" >/dev/null 2>&1; then
    launchctl bootout "system/$LABEL"
    STOPPED=yes
fi

say "Test run of the new release (--once). The first run uploads up to 30 days of clips and can take a while."
if ! as_user "$RELEASE/venv/bin/camera-uploader" --config "$TEST_CONFIG" --once; then
    die "test run failed - see $LOGDIR/uploader.log. Nothing was switched over. Wrong password? rerun with --reconfigure"
fi

if [ -n "$NEW_CONFIG" ]; then
    mv "$NEW_CONFIG" "$CONFIG"
    NEW_CONFIG=""
fi

say "Switching to the new release"
ln -sfn "$RELEASE" "$CURRENT.tmp"
mv -fh "$CURRENT.tmp" "$CURRENT"
install -o root -g wheel -m 644 "$SRC/deploy/$LABEL.plist" "$PLIST"
launchctl bootstrap system "$PLIST"
DONE=yes

say "Keeping the two newest releases"
RELEASES=()
# shellcheck disable=SC2012  # release dirs are plain timestamps
while IFS= read -r r; do RELEASES+=("$r"); done < <(ls -1d "$PREFIX"/releases/* | sort)
for ((i = 0; i < ${#RELEASES[@]} - 2; i++)); do
    [ "${RELEASES[$i]}" = "$(readlink "$CURRENT")" ] || rm -rf "${RELEASES[$i]}"
done

sleep 2
launchctl print "system/$LABEL" | grep -E '^\s*(state|pid) =' || true
say "Done. Log: $LOGDIR/uploader.log"
