#!/usr/bin/env bash
# Sync canonical Understory shared modules into self-contained application repos.
#
# Safety properties:
#   - refuses arbitrary/non-Understory/non-Git targets;
#   - refuses dirty targets unless explicitly overridden;
#   - never silently skips a requested target;
#   - supports a no-write dry run;
#   - writes a content-hash receipt after a real sync;
#   - leaves review and commit as explicit human steps.
#
# Usage:
#   tools/sync-common.sh [--dry-run] [--allow-dirty] /path/to/understory-app [...]
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/sync-common.sh [--dry-run] [--allow-dirty] TARGET [TARGET ...]

  --dry-run      Show rsync changes without writing files or receipts.
  --allow-dirty  Permit a target with existing uncommitted changes.
  -h, --help     Show this help.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail "git is required"
command -v rsync >/dev/null 2>&1 || fail "rsync is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
command -v realpath >/dev/null 2>&1 || fail "realpath is required"

DRY_RUN=0
ALLOW_DIRTY=0
TARGETS=()

while (($#)); do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    --allow-dirty)
      ALLOW_DIRTY=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      TARGETS+=("$@")
      break
      ;;
    -*)
      fail "unknown option: $1"
      ;;
    *)
      TARGETS+=("$1")
      ;;
  esac
  shift
done

((${#TARGETS[@]} > 0)) || { usage >&2; fail "at least one target is required"; }

HERE="$(cd "$(dirname "$0")/.." && pwd -P)"
SOURCE_COMMIT="$(git -C "$HERE" rev-parse HEAD 2>/dev/null || printf 'unknown')"
SOURCE_REPOSITORY="Zheke32174/understory-common"
RECEIPT_NAME=".understory-common-sync-receipt"
MODULES=(common-security common-backup overlay-i2p overlay-lokinet overlay-yggdrasil keystore)

[ -f "$HERE/lint.xml" ] || fail "canonical lint.xml is missing"
[ -d "$HERE/common-security" ] || fail "canonical common-security module is missing"

hash_tree() {
  local root="$1"
  (
    cd "$root"
    find . -type f ! -path './build/*' ! -path '*/build/*' -print0 \
      | LC_ALL=C sort -z \
      | xargs -0 -r sha256sum
  ) | sha256sum | awk '{print $1}'
}

validate_target() {
  local requested="$1"
  local target base top

  [ -e "$requested" ] || fail "target does not exist: $requested"
  target="$(realpath "$requested")"
  [ -d "$target" ] || fail "target is not a directory: $requested"
  [ "$target" != "$HERE" ] || fail "source repository cannot be its own sync target"

  top="$(git -C "$target" rev-parse --show-toplevel 2>/dev/null)" \
    || fail "target is not a Git worktree: $target"
  top="$(realpath "$top")"
  [ "$top" = "$target" ] || fail "target must be the Git worktree root: $target"

  base="$(basename "$target")"
  case "$base" in
    understory-*) ;;
    *) fail "target basename must begin with understory-: $target" ;;
  esac

  [ -f "$target/settings.gradle.kts" ] || [ -f "$target/settings.gradle" ] \
    || fail "target has no Android Gradle settings file: $target"
  [ -d "$target/common-security" ] \
    || fail "target does not vendor common-security and is not a suite sync target: $target"

  if ((ALLOW_DIRTY == 0)) && [ -n "$(git -C "$target" status --porcelain --untracked-files=normal)" ]; then
    fail "target has uncommitted changes (use --allow-dirty only after review): $target"
  fi

  printf '%s\n' "$target"
}

sync_target() {
  local target="$1"
  local module
  local -a rsync_args=(-a --checksum --delete-delay --itemize-changes --exclude build/)

  if ((DRY_RUN)); then
    rsync_args+=(--dry-run)
    printf 'DRY RUN: %s\n' "$target"
  else
    printf 'SYNC: %s\n' "$target"
  fi

  for module in "${MODULES[@]}"; do
    if [ -d "$target/$module" ]; then
      rsync "${rsync_args[@]}" "$HERE/$module/" "$target/$module/"
    fi
  done

  if ((DRY_RUN)); then
    rsync "${rsync_args[@]}" "$HERE/lint.xml" "$target/lint.xml"
    printf 'receipt: not written during dry run\n'
    return
  fi

  cp "$HERE/lint.xml" "$target/lint.xml"

  {
    printf 'schema=1\n'
    printf 'source_repository=%s\n' "$SOURCE_REPOSITORY"
    printf 'source_commit=%s\n' "$SOURCE_COMMIT"
    printf 'target_repository=%s\n' "$(basename "$target")"
    printf 'lint_sha256=%s\n' "$(sha256sum "$target/lint.xml" | awk '{print $1}')"
    for module in "${MODULES[@]}"; do
      if [ -d "$target/$module" ]; then
        printf 'module.%s.sha256=%s\n' "$module" "$(hash_tree "$target/$module")"
      fi
    done
  } > "$target/$RECEIPT_NAME"

  printf 'receipt: %s\n' "$target/$RECEIPT_NAME"
  printf 'review: git -C %q diff --stat && git -C %q diff\n' "$target" "$target"
}

for requested in "${TARGETS[@]}"; do
  target="$(validate_target "$requested")"
  sync_target "$target"
done

printf 'Done. Nothing was committed or pushed. Review every target diff before promotion.\n'
