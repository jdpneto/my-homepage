#!/usr/bin/env bash
#
# Bulk-import a local directory of photos/videos into the memorial gallery.
#
# Rsyncs the local source to a staging path on the VPS, then runs the bulk
# importer (Spring profile "bulkimport") inside a one-shot Docker container
# that shares the same DB and gallery_data volume as the live app.
#
# Idempotent: rsync is incremental, and the importer dedupes by SHA-256, so
# re-running on the same source is safe and skips already-imported files.
#
# Required env:
#   IMPORT_SOURCE        Local directory to import (e.g. ~/Pictures/mae).
#                        A trailing slash is added automatically if missing.
#
# Optional env:
#   IMPORT_UPLOADER      Name attributed to imported items.
#                        Default: empty (items get NULL uploader_name).
#   IMPORT_REMOTE_DIR    Staging path on the VPS. Default: /srv/mae-import
#   DEPLOY_SSH_TARGET    SSH target. Default: davidneto@davidneto.eu
#   DEPLOY_REMOTE_REPO   Path to repo on the VPS. Default: ~/my-homepage
#
# Flags:
#   --skip-rsync   Skip the rsync step (assume the staging dir already exists).
#   --cleanup      After a successful import, rm -rf the staging dir on the VPS.
#   -h | --help    Show help.
#
# Examples:
#   IMPORT_SOURCE=~/Pictures/mae IMPORT_UPLOADER=David ./scripts/bulk-import.sh
#   IMPORT_SOURCE=~/Pictures/mae ./scripts/bulk-import.sh --skip-rsync
#   IMPORT_SOURCE=~/Pictures/mae ./scripts/bulk-import.sh --cleanup

set -euo pipefail

SSH_TARGET="${DEPLOY_SSH_TARGET:-davidneto@davidneto.eu}"
REMOTE_REPO="${DEPLOY_REMOTE_REPO:-~/my-homepage}"
REMOTE_DIR="${IMPORT_REMOTE_DIR:-/srv/mae-import}"
UPLOADER="${IMPORT_UPLOADER:-}"
SOURCE="${IMPORT_SOURCE:-}"

usage() {
    cat <<EOF
Usage: IMPORT_SOURCE=<local-dir> [IMPORT_UPLOADER=<name>] $0 [--skip-rsync] [--cleanup]

Required env:
  IMPORT_SOURCE      Local directory to import (trailing slash auto-added).

Optional env:
  IMPORT_UPLOADER    Name attributed to imported items. Default: empty.
  IMPORT_REMOTE_DIR  Staging path on the VPS. Default: $REMOTE_DIR
  DEPLOY_SSH_TARGET  SSH target. Default: $SSH_TARGET
  DEPLOY_REMOTE_REPO Path to repo on the VPS. Default: $REMOTE_REPO

Flags:
  --skip-rsync   Skip rsync (assume staging dir already populated).
  --cleanup      Wipe staging dir on the VPS after a successful import.

The importer is idempotent (SHA-256 dedupe) — safe to re-run.
EOF
}

SKIP_RSYNC=no
CLEANUP=no
for arg in "$@"; do
    case "$arg" in
        --skip-rsync)  SKIP_RSYNC=yes ;;
        --cleanup)     CLEANUP=yes ;;
        -h|--help)     usage; exit 0 ;;
        *)             echo "unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

MANIFEST_NAME='.taken-at-manifest.tsv'

# Filesystem birthtime is OS-specific and rsync doesn't preserve it, so we
# capture it locally before rsync and ship a manifest sidecar that the
# bulk importer reads as the preferred bucket-date fallback.
case "$(uname -s)" in
    Darwin) stat_birth() { stat -f '%B' "$1" 2>/dev/null; } ;;
    *)      stat_birth() { stat -c '%W' "$1" 2>/dev/null; } ;;  # GNU stat; 0 if unavailable
esac

if [[ "$SKIP_RSYNC" != "yes" ]]; then
    if [[ -z "$SOURCE" ]]; then
        echo "error: IMPORT_SOURCE not set" >&2; usage; exit 2
    fi
    if [[ ! -d "$SOURCE" ]]; then
        echo "error: $SOURCE is not a directory" >&2; exit 2
    fi
    # Strip trailing slash for stable manifest paths, then add it back for rsync.
    case "$SOURCE" in */) SOURCE="${SOURCE%/}" ;; esac

    manifest="$SOURCE/$MANIFEST_NAME"
    echo ">>> Building birthtime manifest at $manifest"
    : > "$manifest"
    entries=0
    skipped=0
    while IFS= read -r -d '' f; do
        rel="${f#./}"
        epoch="$(stat_birth "$f" || true)"
        if [[ -z "$epoch" || "$epoch" == "0" || "$epoch" == "-" ]]; then
            skipped=$((skipped + 1))
            continue
        fi
        printf '%s\t%s\n' "$rel" "$epoch" >> "$manifest"
        entries=$((entries + 1))
    done < <(cd "$SOURCE" && find . -type f \
                 -not -name "$MANIFEST_NAME" \
                 -not -name '.DS_Store' \
                 -print0)
    echo "    $entries files with birthtime captured, $skipped without (will fall back to mtime)"

    echo ">>> Ensuring $REMOTE_DIR exists on $SSH_TARGET"
    ssh -T "$SSH_TARGET" "mkdir -p '$REMOTE_DIR'"

    echo ">>> Rsyncing $SOURCE/ -> $SSH_TARGET:$REMOTE_DIR/"
    # --progress works on both macOS's bundled rsync (2.6.9) and modern
    # rsync 3.x; --info=progress2 is 3.1+ only.
    rsync -avz --progress "$SOURCE/" "$SSH_TARGET:$REMOTE_DIR/"
else
    echo ">>> Skipping rsync (--skip-rsync)"
fi

echo ""
echo ">>> Starting bulk import on $SSH_TARGET"
echo "    staging dir: $REMOTE_DIR"
echo "    uploader:    ${UPLOADER:-<unset, items get NULL uploader>}"
echo ""
echo "    Logs stream back to this terminal. Closing this SSH connection"
echo "    kills the import. For long runs, wrap this script in tmux/screen"
echo "    locally, or ssh in first and invoke the docker compose run from"
echo "    a tmux session on the VPS."
echo ""

# Stream the importer's logs over an SSH-allocated tty so Ctrl-C and
# normal compose output behave correctly.
ssh -tT "$SSH_TARGET" \
    REMOTE_REPO="$REMOTE_REPO" \
    REMOTE_DIR="$REMOTE_DIR" \
    UPLOADER="$UPLOADER" \
    bash <<'REMOTE'
set -euo pipefail
cd "$REMOTE_REPO"

uploader_arg=""
[ -n "$UPLOADER" ] && uploader_arg="--gallery.import.uploader=$UPLOADER"

# The Dockerfile's ENTRYPOINT is `java -jar app.jar`, so args after the
# service name become Spring Boot CLI args appended to it. The bulkimport
# profile activates the BulkImporter ApplicationRunner which walks
# /import and exits when done.
docker compose run --rm \
    -v "$REMOTE_DIR:/import:ro" \
    app \
    --spring.profiles.active=bulkimport \
    --gallery.import.path=/import \
    $uploader_arg
REMOTE

echo ""
if [[ "$CLEANUP" == "yes" ]]; then
    echo ">>> --cleanup: removing $REMOTE_DIR on $SSH_TARGET"
    ssh -T "$SSH_TARGET" "rm -rf '$REMOTE_DIR'"
    echo "    done."
else
    echo ">>> Staging dir kept at $SSH_TARGET:$REMOTE_DIR"
    echo "    Re-run with --cleanup to wipe it after you've verified the gallery."
fi
