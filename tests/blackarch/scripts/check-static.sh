#!/usr/bin/env bash
# Defense check 10 — sanity check on static analysis surface.
# Pass: Tamper, Vault, Crypto, IME, Autofill classes all present in dex.
# This is structural: confirms the defense classes shipped, not just
# their source.
set -eu
# NOTE: deliberately NOT using pipefail here — `grep -q` exits early on
# match, causing `strings` upstream to receive SIGPIPE and exit non-zero.
# With pipefail that would fail the whole pipeline despite the match.
APK="${APK:?APK env var required}"

EXPECTED=(
    "com.understory.security.Tamper"
    "com.understory.security.A11yProbe"
    "com.understory.security.SecureButton"
    "com.understory.security.DeviceProfile"
    "com.understory.security.Totp"
    "com.understory.security.HotpSecret"
    "com.understory.security.Crypto"
    "com.understory.passgen.Vault"
    "com.understory.passgen.BackupFormat"
    "com.understory.passgen.PassgenInputMethodService"
    "com.understory.passgen.PassgenAutofillService"
)

WORK=$(mktemp -d)
trap "rm -rf $WORK" EXIT
unzip -q "$APK" -d "$WORK"

missing=0
for cls in "${EXPECTED[@]}"; do
    # Class descriptors. Kotlin top-level functions compile to `<File>Kt`,
    # so SecureButton (file-level @Composable) lives as SecureButtonKt.
    # Accept either form.
    desc1="L$(echo "$cls" | tr . /);"
    desc2="L$(echo "$cls" | tr . /)Kt;"
    found=false
    for dex in "$WORK"/classes*.dex; do
        if strings -n 12 "$dex" | grep -qFx "$desc1"; then
            found=true; break
        fi
        if strings -n 12 "$dex" | grep -qFx "$desc2"; then
            found=true; break
        fi
        # Also accept as a substring (some dex pools concatenate).
        if strings -n 12 "$dex" | grep -qF "$desc1"; then
            found=true; break
        fi
        if strings -n 12 "$dex" | grep -qF "$desc2"; then
            found=true; break
        fi
    done
    if ! $found; then
        echo "  MISSING $cls"
        missing=$((missing+1))
    fi
done

if [[ $missing -eq 0 ]]; then
    echo "  all defense classes present in dex"
    exit 0
else
    exit 1
fi
