#!/bin/bash
#
# Stop and remove the camera-uploader service.
#
#   sudo ./deploy/uninstall.sh           # keeps config, state and logs
#   sudo ./deploy/uninstall.sh --purge   # removes everything, including the config with passwords
#
set -euo pipefail

LABEL=eu.davidneto.camera-uploader
PLIST=/Library/LaunchDaemons/$LABEL.plist

[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }

launchctl bootout "system/$LABEL" 2>/dev/null || true
rm -f "$PLIST"
echo ">>> service removed"

if [ "${1:-}" = "--purge" ]; then
    rm -rf /opt/camera-uploader /usr/local/etc/camera-uploader /usr/local/var/camera-uploader \
           /usr/local/var/log/camera-uploader
    echo ">>> code, config, state and logs removed"
fi
