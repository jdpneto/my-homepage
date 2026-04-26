#!/usr/bin/env bash
#
# One-shot helper to add the memorial-gallery env vars to the VPS .env file.
#
# Prompts for MAE_TITLE, MAE_PASSWORD, WEBDAV_DROP_USERNAME, WEBDAV_DROP_PASSWORD,
# and GALLERY_ROOT_DIR, then appends them to the remote .env. After running this
# once, run ./scripts/deploy.sh so the app picks up the new variables.
#
# Re-running is safe: any variable already present in the remote .env is skipped.
#
# Config (override via env var, defaults match scripts/deploy.sh):
#   DEPLOY_SSH_TARGET   SSH target. Default: "davidneto@davidneto.eu"
#   DEPLOY_REMOTE_REPO  Path to repo on the VPS. Default: "~/my-homepage"
#   DEPLOY_REMOTE_ENV   Path to the .env on the VPS. Default: "$REMOTE_REPO/.env"

set -euo pipefail

SSH_TARGET="${DEPLOY_SSH_TARGET:-davidneto@davidneto.eu}"
REMOTE_REPO="${DEPLOY_REMOTE_REPO:-~/my-homepage}"
REMOTE_ENV="${DEPLOY_REMOTE_ENV:-$REMOTE_REPO/.env}"

prompt_plain() {
    local var="$1" label="$2" default="${3:-}"
    local value
    if [[ -n "$default" ]]; then
        read -rp "$label [$default]: " value
        value="${value:-$default}"
    else
        read -rp "$label: " value
    fi
    [[ -z "$value" ]] && { echo "error: $var must not be empty" >&2; exit 1; }
    printf -v "$var" '%s' "$value"
}

prompt_secret() {
    local var="$1" label="$2"
    local value confirm
    read -rsp "$label: " value; echo
    [[ -z "$value" ]] && { echo "error: $var must not be empty" >&2; exit 1; }
    read -rsp "Confirm $var: " confirm; echo
    [[ "$value" != "$confirm" ]] && { echo "error: $var values do not match" >&2; exit 1; }
    case "$value" in *$'\n'*) echo "error: $var must not contain a newline" >&2; exit 1 ;; esac
    printf -v "$var" '%s' "$value"
}

cat <<EOF
This will append the memorial-gallery variables to the .env file on:

  $SSH_TARGET : $REMOTE_ENV

Variables already present in the remote .env will be skipped.
You'll be asked to confirm before anything is written.

EOF

prompt_plain  MAE_TITLE             "MAE_TITLE (page heading, e.g. 'In memory of Maria')" "In memory of"
prompt_secret MAE_PASSWORD          "MAE_PASSWORD (shared password for /mae — give to family)"
prompt_plain  WEBDAV_DROP_USERNAME  "WEBDAV_DROP_USERNAME (WebDAV drop-folder user)" "mae-drop"
prompt_secret WEBDAV_DROP_PASSWORD  "WEBDAV_DROP_PASSWORD (separate from MAE_PASSWORD)"
prompt_plain  GALLERY_ROOT_DIR      "GALLERY_ROOT_DIR (path inside the container)" "/app/gallery"

mask() { printf '%s' "$1" | sed 's/./*/g'; }

cat <<EOF

About to append (passwords shown masked):

  MAE_TITLE=$MAE_TITLE
  MAE_PASSWORD=$(mask "$MAE_PASSWORD")
  WEBDAV_DROP_USERNAME=$WEBDAV_DROP_USERNAME
  WEBDAV_DROP_PASSWORD=$(mask "$WEBDAV_DROP_PASSWORD")
  GALLERY_ROOT_DIR=$GALLERY_ROOT_DIR

EOF
read -rp "Type 'yes' to append: " confirm
[[ "$confirm" != "yes" ]] && { echo "aborted." >&2; exit 1; }

# Send the script to the remote via stdin (so secrets are not in process args
# visible to `ps` on either side). Values are shell-escaped via printf '%q'.
ssh -T "$SSH_TARGET" bash <<REMOTE
set -euo pipefail
REMOTE_ENV=$(printf '%q' "$REMOTE_ENV")

if [ ! -f "\$REMOTE_ENV" ]; then
    echo "error: \$REMOTE_ENV does not exist on the remote" >&2
    exit 1
fi

append_if_missing() {
    local key="\$1" value="\$2"
    if grep -qE "^\${key}=" "\$REMOTE_ENV"; then
        echo "  skip   \$key (already set in \$REMOTE_ENV)"
    else
        printf '%s=%s\n' "\$key" "\$value" >> "\$REMOTE_ENV"
        echo "  added  \$key"
    fi
}

# Ensure the file ends with a newline before appending.
if [ -s "\$REMOTE_ENV" ] && [ "\$(tail -c1 "\$REMOTE_ENV" | xxd -p)" != "0a" ]; then
    printf '\n' >> "\$REMOTE_ENV"
fi

append_if_missing MAE_TITLE             $(printf '%q' "$MAE_TITLE")
append_if_missing MAE_PASSWORD          $(printf '%q' "$MAE_PASSWORD")
append_if_missing WEBDAV_DROP_USERNAME  $(printf '%q' "$WEBDAV_DROP_USERNAME")
append_if_missing WEBDAV_DROP_PASSWORD  $(printf '%q' "$WEBDAV_DROP_PASSWORD")
append_if_missing GALLERY_ROOT_DIR      $(printf '%q' "$GALLERY_ROOT_DIR")

chmod 600 "\$REMOTE_ENV"
echo "  perms  600 on \$REMOTE_ENV"
REMOTE

cat <<EOF

Done. Next steps:

  1. Add a DNS A record for drop.davidneto.eu pointing at your VPS, so
     Caddy can issue a Let's Encrypt cert for the new host.

  2. Deploy the gallery code:
       ./scripts/deploy.sh

  3. Visit https://davidneto.eu/mae and log in with MAE_PASSWORD.

  4. (Optional) Bulk-import existing photos per the runbook in
     docs/superpowers/plans/2026-04-26-memorial-gallery-implementation.md
     (Task 24).
EOF
