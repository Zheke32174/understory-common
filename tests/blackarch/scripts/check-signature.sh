#!/usr/bin/env bash
# Defense check 13 — signature audit.
# Pass: v2 verified, cert digest matches the pin in Tamper.kt.
set -euo pipefail
APK="${APK:?APK env var required}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/apksigner}"

OUT=$("$APKSIGNER" verify --verbose --print-certs "$APK")
if ! grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$OUT"; then
    echo "  v2 scheme not verified"
    exit 1
fi

ACTUAL=$(grep "Signer #1 certificate SHA-256 digest:" <<<"$OUT" | awk '{print $NF}')
# Tamper.kt has the constant on multiple lines:
#   private const val EXPECTED_CERT_SHA256 =
#       "5a4e9030..."
# Just grep for any 64-char hex string in the file; there's only one.
PINNED=$(grep -oE '[a-f0-9]{64}' \
    ../../../common-security/src/main/java/com/understory/security/Tamper.kt \
    | head -1)

if [[ "$ACTUAL" == "$PINNED" ]]; then
    echo "  v2 verified, cert digest matches pin: $PINNED"
    exit 0
else
    echo "  cert digest mismatch:"
    echo "    actual: $ACTUAL"
    echo "    pinned: $PINNED"
    exit 1
fi
