#!/usr/bin/env bash
# Run every scriptable defense check against the dist APK.
# Doesn't require a connected device for most of these — only the
# adb-backup and install-dialog checks do.
#
# Usage:
#   cd android/tests/blackarch/scripts
#   ./run-all-scriptable.sh

set -euo pipefail
cd "$(dirname "$0")"
APK="${APK:-../../../dist/passgen.apk}"

if [[ ! -f "$APK" ]]; then
    echo "FAIL  $APK not found. Build first."
    exit 1
fi

echo "=== passgen defense scriptable suite ==="
echo "APK: $(realpath "$APK")"
echo

pass=0
fail=0

run() {
    local name="$1" script="$2"
    echo "--- $name ---"
    if APK="$APK" bash "$script"; then
        echo "PASS  $name"
        pass=$((pass+1))
    else
        echo "FAIL  $name"
        fail=$((fail+1))
    fi
    echo
}

run "11-permissions"   ./check-permissions.sh
run "13-signature"     ./check-signature.sh
run "14-strings"       ./check-strings.sh
run "12-debugger"      ./check-debuggable.sh
run "10-static"        ./check-static.sh

echo "=== summary ==="
echo "PASS: $pass    FAIL: $fail"
[[ $fail -eq 0 ]]
