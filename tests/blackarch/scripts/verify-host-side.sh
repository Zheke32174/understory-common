#!/usr/bin/env bash
# Run every BlackArch test that can be performed entirely from a host
# machine — no phone required. Pass criteria are documented in the
# corresponding NN-*.md runbooks.
#
# Tools required (install per SETUP.md):
#   apksigner, aapt2  (Android SDK build-tools)
#   apktool           (apt: apktool)
#   jadx              (download or apt)
#   keytool           (JDK)
#   unzip, strings    (coreutils)
#
# Usage:
#   cd android/tests/blackarch/scripts
#   ./verify-host-side.sh

set -eu
cd "$(dirname "$0")"
APK="${APK:-../../../dist/passgen.apk}"
ROOT="$(cd ../../../.. && pwd)"
WORK="../workbench"
mkdir -p "$WORK"

if [[ ! -f "$APK" ]]; then
    echo "FAIL  $APK not found"
    exit 1
fi

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

# Static / no-device tests
run "11-permissions"   ./check-permissions.sh
run "13-signature"     ./check-signature.sh
run "14-strings"       ./check-strings.sh
run "12-debugger"      ./check-debuggable.sh
run "10-static"        ./check-static.sh

# Decompilation + repackaging — host only, no phone needed
run "10-jadx"          ./check-jadx.sh
run "02-resign"        ./check-resign.sh

echo "=== summary ==="
echo "PASS: $pass    FAIL: $fail"
[[ $fail -eq 0 ]]
