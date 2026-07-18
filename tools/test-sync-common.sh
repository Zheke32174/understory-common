#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
SYNC="$ROOT/tools/sync-common.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  printf 'test failure: %s\n' "$*" >&2
  exit 1
}

expect_fail() {
  local label="$1"
  shift
  if "$@" >"$TMP/$label.out" 2>"$TMP/$label.err"; then
    fail "$label unexpectedly succeeded"
  fi
}

make_repo() {
  local target="$1"
  mkdir -p "$target/common-security/src"
  printf 'pluginManagement {}\n' > "$target/settings.gradle.kts"
  printf 'stale\n' > "$target/common-security/src/stale.txt"
  printf 'old lint\n' > "$target/lint.xml"
  git -C "$target" init -q
  git -C "$target" config user.name 'MODOS fixture'
  git -C "$target" config user.email 'modos-fixture@example.invalid'
  git -C "$target" add .
  git -C "$target" commit -qm 'fixture baseline'
}

bash -n "$SYNC"
expect_fail no-target "$SYNC"

BAD="$TMP/not-a-suite"
make_repo "$BAD"
expect_fail arbitrary-target "$SYNC" "$BAD"
grep -q 'basename must begin with understory-' "$TMP/arbitrary-target.err" \
  || fail 'arbitrary-target refusal did not explain the boundary'

TARGET="$TMP/understory-fixture"
make_repo "$TARGET"

"$SYNC" --dry-run "$TARGET" >"$TMP/dry-run.out"
[ -f "$TARGET/common-security/src/stale.txt" ] \
  || fail 'dry run deleted a destination file'
[ ! -e "$TARGET/.understory-common-sync-receipt" ] \
  || fail 'dry run wrote a receipt'
[ -z "$(git -C "$TARGET" status --porcelain)" ] \
  || fail 'dry run modified the target worktree'
grep -q 'DRY RUN:' "$TMP/dry-run.out" \
  || fail 'dry run did not identify itself'

printf 'dirty\n' > "$TARGET/local-dirty.txt"
expect_fail dirty-target "$SYNC" "$TARGET"
grep -q 'uncommitted changes' "$TMP/dirty-target.err" \
  || fail 'dirty-target refusal did not explain the boundary'
rm "$TARGET/local-dirty.txt"

"$SYNC" "$TARGET" >"$TMP/real-sync.out"
[ ! -e "$TARGET/common-security/src/stale.txt" ] \
  || fail 'real sync did not remove stale vendored content'
[ -f "$TARGET/.understory-common-sync-receipt" ] \
  || fail 'real sync did not write a receipt'
grep -q '^schema=1$' "$TARGET/.understory-common-sync-receipt" \
  || fail 'receipt schema is missing'
grep -q '^source_repository=Zheke32174/understory-common$' "$TARGET/.understory-common-sync-receipt" \
  || fail 'receipt source repository is missing'
grep -Eq '^source_commit=[0-9a-f]{40}$' "$TARGET/.understory-common-sync-receipt" \
  || fail 'receipt source commit is not pinned'
grep -Eq '^module\.common-security\.sha256=[0-9a-f]{64}$' "$TARGET/.understory-common-sync-receipt" \
  || fail 'receipt common-security hash is missing'
[ -n "$(git -C "$TARGET" status --porcelain)" ] \
  || fail 'real sync produced no reviewable worktree change'
grep -q 'Nothing was committed or pushed' "$TMP/real-sync.out" \
  || fail 'real sync did not preserve the explicit review boundary'

printf 'sync-common tests passed\n'
