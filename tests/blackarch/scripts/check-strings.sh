#!/usr/bin/env bash
# Defense check 14 — extract strings from classes.dex, look for obvious
# embedded credentials.
# Pass: no hits matching credential-shaped patterns.
set -eu
APK="${APK:?APK env var required}"

WORK=$(mktemp -d)
trap "rm -rf $WORK" EXIT

unzip -q "$APK" -d "$WORK"

# Patterns that would indicate a hardcoded credential. We deliberately
# don't match generic things like "password" because user-facing strings
# like "Master password" are expected.
PATTERNS=(
    'api[_-]?key[[:space:]]*=[[:space:]]*"[^"]+"'
    'secret[[:space:]]*=[[:space:]]*"[^"]+"'
    'token[[:space:]]*=[[:space:]]*"[^"]{20,}"'
    'aws_access_key_id'
    'aws_secret_access_key'
    'sk-[a-zA-Z0-9]{30,}'
    'ghp_[a-zA-Z0-9]{30,}'
    'AIza[0-9A-Za-z_-]{35}'
)

hits=0
for dex in "$WORK"/classes*.dex; do
    [[ -f "$dex" ]] || continue
    for pat in "${PATTERNS[@]}"; do
        if matches=$(strings -n 12 "$dex" | grep -E "$pat" | head); then
            if [[ -n "$matches" ]]; then
                echo "  HIT $pat in $(basename "$dex"):"
                echo "$matches" | sed 's/^/    /'
                hits=$((hits+1))
            fi
        fi
    done
done

if [[ $hits -eq 0 ]]; then
    echo "  no embedded credential patterns found"
    exit 0
else
    exit 1
fi
