#!/usr/bin/env bash
# Sync the canonical shared modules from this repo into the per-app
# Understory repos (which vendor them for self-contained builds).
#
# Usage: tools/sync-common.sh /path/to/understory-aegis [/path/to/understory-passgen ...]
# Then review + commit + push in each app repo.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
for target in "$@"; do
  [ -d "$target" ] || { echo "skip $target (not a dir)"; continue; }
  for m in common-security common-backup overlay-i2p overlay-lokinet overlay-yggdrasil keystore; do
    if [ -d "$target/$m" ]; then
      rsync -a --delete --exclude build/ "$HERE/$m/" "$target/$m/"
      echo "synced $m -> $target"
    fi
  done
  cp "$HERE/lint.xml" "$target/lint.xml"
done
echo "Done. Review diffs in each target repo, then commit + push."
