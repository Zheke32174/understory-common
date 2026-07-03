#!/usr/bin/env bash
# Defense check 13 — signature audit.
# Pass: v2 verified, cert digest matches the variant's pin in SuitePins.kt.
set -euo pipefail
APK="${APK:?APK env var required}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/apksigner}"

OUT=$("$APKSIGNER" verify --verbose --print-certs "$APK")
if ! grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$OUT"; then
    echo "  v2 scheme not verified"
    exit 1
fi

ACTUAL=$(grep "Signer #1 certificate SHA-256 digest:" <<<"$OUT" | awk '{print $NF}')
# SuitePins.kt holds BOTH pins, each on the line after its constant name:
#   const val DEBUG_CERT_SHA256 =
#       "aba68a81..."
# Pick the pin for the APK's variant: "release" in the filename → release
# pin, anything else → debug pin (mirrors verifyCertPin's selection).
case "$(basename "$APK")" in
    *release*) PIN_NAME=RELEASE_CERT_SHA256 ;;
    *)         PIN_NAME=DEBUG_CERT_SHA256 ;;
esac
PINNED=$(grep -A1 "$PIN_NAME" \
    ../../../common-security/src/main/java/com/understory/security/SuitePins.kt \
    | grep -oE '[a-f0-9]{64}' | head -1)

if [[ "$ACTUAL" == "$PINNED" ]]; then
    echo "  v2 verified, cert digest matches pin: $PINNED"
    exit 0
else
    echo "  cert digest mismatch:"
    echo "    actual: $ACTUAL"
    echo "    pinned: $PINNED"
    exit 1
fi
