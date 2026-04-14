#!/usr/bin/env bash
#
# Redeploy the homepage + WebDAV stack to a Hetzner VPS.
#
# Default (safe):    pull main, rebuild app image, restart containers.
#                    All data volumes are preserved.
# --full (nuclear):  pull main, wipe pgdata/uploads/webdav_data, rebuild.
#                    Keeps caddy_data so Let's Encrypt certs are not re-issued.
#                    Destroys: blog posts, static pages, social links,
#                              site settings, uploads, WebDAV users and files.
#
# Config (override via env var or edit defaults below):
#   DEPLOY_SSH_TARGET       SSH target. Default: "davidneto@davidneto.eu"
#   DEPLOY_REMOTE_REPO      Path to repo on the VPS. Default: "~/my-homepage"
#   DEPLOY_COMPOSE_PROJECT  Compose project name (volume prefix).
#                           Default: "my-homepage" (the repo dir name).

set -euo pipefail

SSH_TARGET="${DEPLOY_SSH_TARGET:-davidneto@davidneto.eu}"
REMOTE_REPO="${DEPLOY_REMOTE_REPO:-~/my-homepage}"
COMPOSE_PROJECT="${DEPLOY_COMPOSE_PROJECT:-my-homepage}"

usage() {
    cat <<EOF
Usage: $0 [--full] [--yes]

  (default)    Safe redeploy: pull main, rebuild image, restart. Keeps data.
  --full       NUCLEAR: also wipe pgdata, uploads, webdav_data volumes.
               Caddy/LE certs (caddy_data) are preserved. Requires confirmation.
  --yes        Skip the confirmation prompt for --full.
  -h | --help  Print this help.

Target: $SSH_TARGET : $REMOTE_REPO   (compose project: $COMPOSE_PROJECT)
EOF
}

MODE=safe
ASSUME_YES=no
for arg in "$@"; do
    case "$arg" in
        --full)      MODE=full ;;
        --yes|-y)    ASSUME_YES=yes ;;
        -h|--help)   usage; exit 0 ;;
        *)           echo "unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

if [[ "$MODE" == "full" && "$ASSUME_YES" != "yes" ]]; then
    cat >&2 <<EOF
!!! DESTRUCTIVE REDEPLOY TO $SSH_TARGET !!!

This will WIPE the following Docker volumes on the VPS:
  - ${COMPOSE_PROJECT}_pgdata       (blog posts, pages, social links, site settings, WebDAV users)
  - ${COMPOSE_PROJECT}_uploads      (site photo and other uploaded assets)
  - ${COMPOSE_PROJECT}_webdav_data  (every file any WebDAV user ever uploaded)

Preserved:
  - ${COMPOSE_PROJECT}_caddy_data   (Let's Encrypt certs — avoids LE rate limit)

This is irreversible. Make sure you have a pg_dump or volume backup.

EOF
    read -rp "Type 'redeploy' to proceed: " confirm
    if [[ "$confirm" != "redeploy" ]]; then
        echo "aborted." >&2
        exit 1
    fi
fi

echo ">>> Deploying to $SSH_TARGET (mode: $MODE)"

ssh -T "$SSH_TARGET" \
    REMOTE_REPO="$REMOTE_REPO" \
    COMPOSE_PROJECT="$COMPOSE_PROJECT" \
    MODE="$MODE" \
    bash <<'REMOTE'
set -euo pipefail
cd "$REMOTE_REPO"

echo ">>> git fetch && reset to origin/main"
git fetch origin
git reset --hard origin/main

if [ "$MODE" = "full" ]; then
    echo ">>> compose down"
    docker compose down
    echo ">>> wiping data volumes (keeping caddy_data)"
    for v in "${COMPOSE_PROJECT}_pgdata" "${COMPOSE_PROJECT}_uploads" "${COMPOSE_PROJECT}_webdav_data"; do
        docker volume rm "$v" 2>/dev/null && echo "  removed $v" || echo "  $v: not present"
    done
fi

echo ">>> pulling base images (caddy, postgres)"
docker compose pull caddy postgres

echo ">>> rebuilding app image (no cache)"
docker compose build --no-cache app

echo ">>> starting stack"
docker compose up -d

echo ">>> waiting for services to settle"
sleep 8

echo ">>> compose ps"
docker compose ps

echo ">>> last 40 lines of app logs"
docker compose logs --tail=40 app
REMOTE

echo ""
echo ">>> Post-deploy sanity checks"

check() {
    local desc="$1"; shift
    local expected="$1"; shift
    local code
    code="$(curl -ksSI -o /dev/null -w '%{http_code}' "$@" || true)"
    if [ "$code" = "$expected" ]; then
        echo "  OK    $desc ($code)"
    else
        echo "  FAIL  $desc (got $code, expected $expected)"
    fi
}

check "davidneto.eu homepage"                    "200" "https://davidneto.eu/"
check "www redirect / passthrough"               "200" "https://www.davidneto.eu/"
check "cloud.davidneto.eu unauth (expect 401)"   "401" -X PROPFIND "https://cloud.davidneto.eu/"
check "admin login page reachable"               "200" "https://davidneto.eu/admin/login"

echo ""
if [ "$MODE" = "full" ]; then
    cat <<EOF
>>> Nuclear redeploy complete. Next steps:
    1. Log in at https://davidneto.eu/admin/login with the ADMIN_USERNAME
       and ADMIN_PASSWORD from the VPS .env file.
    2. Recreate WebDAV users at https://davidneto.eu/admin/webdav-users.
    3. Recreate blog posts, pages, social links as needed.
EOF
else
    echo ">>> Safe redeploy complete. All data preserved."
fi
