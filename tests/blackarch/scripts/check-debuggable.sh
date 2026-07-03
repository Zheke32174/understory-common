#!/usr/bin/env bash
# Defense check 12 — debuggable flag in the manifest.
# Pass: android:debuggable is absent or explicitly false.
set -euo pipefail
APK="${APK:?APK env var required}"
AAPT2="${AAPT2:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/aapt2}"

OUT=$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null)

# In aapt2 xmltree output, an "application-debuggable" line appears only
# when debuggable=true. aapt2 dump badging similarly emits the badge.
if grep -qE 'application-debuggable|"debuggable"\([^)]*\)=true' <<<"$OUT"; then
    echo "  debuggable=true detected — FAIL"
    exit 1
fi

BADGING=$("$AAPT2" dump badging "$APK" 2>/dev/null)
if grep -q "^application-debuggable" <<<"$BADGING"; then
    echo "  application-debuggable badge present — FAIL"
    exit 1
fi

echo "  debuggable flag absent or false"
exit 0
