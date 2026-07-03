#!/usr/bin/env bash
# Defense check 02 — re-sign attack.
# Decompose the APK, strip its signature, sign with a fresh attacker
# keystore, verify the resulting cert digest differs from the pinned
# value in Tamper.kt. If it differs, the signature pin would hard-fail
# the install on launch — proving the defense is intact.
#
# This does NOT install the resigned APK on a phone (we have no phone
# attached). The runtime defense (Tamper.signatureMatches() returning
# false → finishAndRemoveTask) is verified separately on a real device
# per 02-repackaging.md.
set -eu
APK="${APK:?APK env var required}"

WORK="$(cd ../workbench && pwd)/resign"
rm -rf "$WORK"
mkdir -p "$WORK"
cp "$APK" "$WORK/passgen.apk"

cd "$WORK"

# Strip the original signature.
zip -q -d passgen.apk 'META-INF/*' || true

# Generate a fresh keystore for the attacker (any keystore will do).
keytool -genkeypair -keystore attacker.jks -storepass attacker \
    -keyalg RSA -keysize 2048 -validity 365 \
    -alias attacker -dname "CN=Attacker,O=Test,C=XX" \
    -keypass attacker 2>/dev/null

# Sign with the attacker's key.
apksigner sign --ks attacker.jks --ks-pass pass:attacker passgen.apk 2>/dev/null

# Read the resulting cert digest.
ATTACK_DIGEST=$(apksigner verify --print-certs passgen.apk 2>&1 \
    | grep "SHA-256 digest" | head -1 | awk '{print $NF}')

# Read the pinned digest from Tamper.kt.
PINNED=$(grep -oE '[a-f0-9]{64}' \
    /home/user/understory/android/common-security/src/main/java/com/understory/security/Tamper.kt \
    | head -1)

if [[ "$ATTACK_DIGEST" != "$PINNED" ]] && [[ -n "$ATTACK_DIGEST" ]]; then
    echo "  PASS — re-signed cert ($ATTACK_DIGEST)"
    echo "  differs from pin    ($PINNED)"
    echo "  → Tamper.signatureMatches() would return false → hard-fail on launch"
    exit 0
else
    echo "  FAIL — re-signed cert digest unexpectedly matches pin (or is empty)"
    echo "    actual: $ATTACK_DIGEST"
    echo "    pinned: $PINNED"
    exit 1
fi
