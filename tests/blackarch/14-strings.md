# 14 — String extraction sweep

**Threat class**: low-effort secret discovery
**Tools**: `unzip`, `strings`, GNU coreutils.

**passgen defense**: nothing secret is hardcoded. Vault is encrypted at
rest. Master KEK is generated at runtime and stored Keystore-wrapped.
Reveal-lock no longer exists (it was typed; replaced by BiometricPrompt).

This test is the cheapest baseline check, and if it ever produces a
hit, that's a critical defect.

## Test

```bash
APK=android/dist/passgen.apk
WORK=/tmp/passgen-strings
rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
unzip -q "$OLDPWD/$APK"
for f in classes*.dex; do
    echo "=== $f ==="
    strings -n 12 "$f" | grep -iE \
        '(api[_-]?key|secret|token|bearer|aws_|sk-|ghp_|password=|pw=)' \
        | grep -vE '^(@|Lorg|Lkotlin|Landroid|res/)'
done
```

**Expected output**: nothing. The grep should produce zero hits beyond
debug noise. If it hits anything that LOOKS like a credential, inspect:
either it's a false positive (BC's test vectors, Android framework
constants, AndroidX sample data) or a real defect.

## Pass/fail

✅ Sweep finds zero hardcoded credentials in our code
