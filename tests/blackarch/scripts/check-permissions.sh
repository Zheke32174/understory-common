#!/usr/bin/env bash
# Defense check 11 — permission audit.
# Pass: only USE_BIOMETRIC + the AGP-generated signature permission appear.
set -euo pipefail
APK="${APK:?APK env var required}"
AAPT2="${AAPT2:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/aapt2}"

ALLOWED=(
    "android.permission.USE_BIOMETRIC"
    "com.understory.passgen.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

mapfile -t ACTUAL < <("$AAPT2" dump badging "$APK" 2>/dev/null \
    | awk -F"'" '/^uses-permission/ {print $2}')

ok=true
for p in "${ACTUAL[@]}"; do
    found=false
    for a in "${ALLOWED[@]}"; do
        [[ "$p" == "$a" ]] && found=true && break
    done
    if ! $found; then
        echo "  unexpected permission: $p"
        ok=false
    fi
done

if $ok; then
    echo "  permissions: ${#ACTUAL[@]} declared, all in allowlist"
    exit 0
else
    exit 1
fi
