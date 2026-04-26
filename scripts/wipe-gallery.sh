#!/usr/bin/env bash
#
# Wipe ALL gallery state on the VPS to start fresh.
#
# Removes:
#   - All rows from gallery_item (table preserved, IDs reset).
#   - All files under /app/gallery/{originals,thumbs,display,_tmp,_drop}
#     (the dirs are recreated empty).
#   - $IMPORT_REMOTE_DIR (staging dir for the bulk importer).
#
# Does NOT touch:
#   - admin login, blog posts, static pages, social links, site settings.
#   - WebDAV users or their per-user files.
#   - Caddy certs.
#
# Use after schema/bucket-logic changes that require a clean re-import.
#
# Config (override via env, defaults match scripts/deploy.sh):
#   DEPLOY_SSH_TARGET   SSH target.            Default: davidneto@davidneto.eu
#   DEPLOY_REMOTE_REPO  Repo path on the VPS.  Default: ~/my-homepage
#   IMPORT_REMOTE_DIR   Staging dir on the VPS. Default: /srv/mae-import
#
# Flag:
#   --yes | -y    Skip the confirmation prompt.

set -euo pipefail

SSH_TARGET="${DEPLOY_SSH_TARGET:-davidneto@davidneto.eu}"
REMOTE_REPO="${DEPLOY_REMOTE_REPO:-~/my-homepage}"
IMPORT_REMOTE_DIR="${IMPORT_REMOTE_DIR:-/srv/mae-import}"

ASSUME_YES=no
for arg in "$@"; do
    case "$arg" in
        --yes|-y)  ASSUME_YES=yes ;;
        -h|--help)
            cat <<EOF
Usage: $0 [--yes]

Wipe gallery DB rows + stored files + staging dir on the VPS.
Does not touch any other application data.

Target: $SSH_TARGET
        repo:    $REMOTE_REPO
        staging: $IMPORT_REMOTE_DIR
EOF
            exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

if [[ "$ASSUME_YES" != "yes" ]]; then
    cat >&2 <<EOF
!!! DESTRUCTIVE GALLERY WIPE on $SSH_TARGET !!!

This will:
  - TRUNCATE gallery_item (drops every row, resets IDs).
  - Empty /app/gallery/{originals,thumbs,display,_tmp,_drop}.
  - rm -rf $IMPORT_REMOTE_DIR (the bulk-importer staging dir).

It will NOT touch admin/blog/page/WebDAV-user data or Caddy certs.

Irreversible. If captions or any gallery state is worth keeping,
take a pg_dump first.

EOF
    read -rp "Type 'wipe' to proceed: " confirm
    [[ "$confirm" != "wipe" ]] && { echo "aborted." >&2; exit 1; }
fi

ssh -T "$SSH_TARGET" \
    REMOTE_REPO="$REMOTE_REPO" \
    IMPORT_REMOTE_DIR="$IMPORT_REMOTE_DIR" \
    bash <<'REMOTE'
set -euo pipefail
cd "$REMOTE_REPO"

echo ">>> TRUNCATE gallery_item"
# Run inside the postgres container so $POSTGRES_USER/$POSTGRES_DB resolve
# from the container's environment (set from .env via docker-compose).
docker compose exec -T postgres bash -c '
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
         -c "TRUNCATE gallery_item RESTART IDENTITY;"
'

echo ">>> Emptying /app/gallery subdirs"
docker compose exec -T app sh -c '
    set -e
    cd /app/gallery
    rm -rf originals/* thumbs/* display/* _tmp/* _drop/*
    mkdir -p originals thumbs display _tmp _drop/_failed
    echo "  remaining:"
    ls -la
'

echo ">>> rm -rf $IMPORT_REMOTE_DIR (host filesystem)"
if [ -e "$IMPORT_REMOTE_DIR" ]; then
    rm -rf "$IMPORT_REMOTE_DIR" || \
        echo "  could not remove (try as root or with sudo)"
else
    echo "  $IMPORT_REMOTE_DIR did not exist — nothing to do"
fi
REMOTE

echo ""
echo ">>> Wipe complete. Next steps:"
echo "    1. ./scripts/deploy.sh        (if you haven't redeployed the latest code)"
echo "    2. ./scripts/bulk-import.sh   (re-stage and re-ingest the photos)"
