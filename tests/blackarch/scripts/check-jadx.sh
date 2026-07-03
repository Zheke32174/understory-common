#!/usr/bin/env bash
# Defense check 10 (jadx variant) — decompile the APK, search for
# credential-shaped strings in OUR namespace, plus cloud API key
# patterns anywhere.
# Pass: only intentional public strings (cert pin, Keystore aliases,
# character pools, root-marker paths) appear. Zero cloud API key matches.
set -eu
APK="${APK:?APK env var required}"
JADX="${JADX:-jadx}"

WORK="../workbench/jadx-out"
rm -rf "$WORK"
# jadx returns non-zero on partial-decompile failures (some classes
# never decompile cleanly — normal for any non-trivial APK). What we
# care about is whether the sources directory was produced.
"$JADX" -q --output-dir "$WORK" "$APK" >/dev/null 2>&1 || true
if [[ ! -d "$WORK/sources/com/understory" ]]; then
    echo "  jadx produced no output for com/understory namespace"
    exit 1
fi

# Pattern 1: cloud API key shapes — any hit here is a failure.
PATTERNS='AKIA[0-9A-Z]{16}|ghp_[a-zA-Z0-9]{30,}|sk-[a-zA-Z0-9]{30,}|AIza[0-9A-Za-z_-]{35}|xoxb-|xoxp-|aws_secret_access_key.*=.*[a-zA-Z0-9]{20,}'
CLOUD_HITS=$(grep -rEn "$PATTERNS" "$WORK/sources" 2>/dev/null | head)
if [[ -n "$CLOUD_HITS" ]]; then
    echo "  FAIL — cloud API key patterns matched:"
    echo "$CLOUD_HITS" | sed 's/^/    /'
    exit 1
fi

# Pattern 2: long string literals in OUR namespace. Any hit here must
# be reviewable against a known-allowed list.
ALLOWED_REGEX='deviceAuthEncryptCipher|deviceAuthDecryptCipher|passgen_vault_(device_auth|wrap)_v1|abcdefghijklmnopqrstuvwxyz|ABCDEFGHIJKLMNOPQRSTUVWXYZ|0123456789|!@#\$%\^&\*|5a4e9030c9b4a88fa5fe857b4f86f8d6fd7a9f257ea4c02f541a2490e5083588|/(system|sbin|data|cache)/|vault_master_kek_b64|totp_secret_b64|reveal_lock_(hash|salt)|exported_at|otpauth://totp/'

# Kotlin @Metadata annotations contain unicode-encoded type signatures
# for runtime reflection — every Kotlin class has one. We filter the
# entire annotation block (begins with @Metadata, often spans multiple
# fields). Same with kotlinx.serialization @SerialName etc.
UNK_HITS=$(grep -rEn '"[A-Za-z0-9+/=_-]{20,}"' \
    --include='*.java' --include='*.kt' \
    "$WORK/sources/com/understory" 2>/dev/null \
    | grep -vE '@Metadata|kotlin/Metadata|d1 = \{|d2 = \{' \
    | grep -vE "$ALLOWED_REGEX" \
    | head)

if [[ -n "$UNK_HITS" ]]; then
    echo "  REVIEW — unknown long string literals in com/understory:"
    echo "$UNK_HITS" | sed 's/^/    /'
    echo "  (if these are intentional public strings, add to ALLOWED_REGEX)"
    exit 1
fi

echo "  PASS — only known-public strings; no cloud key patterns"
exit 0
