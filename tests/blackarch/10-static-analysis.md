# 10 — Static analysis (jadx / apktool / strings)

**Threat class**: reverse engineering of the shipped APK
**Tools**: jadx, apktool, GNU `strings`, Bytecode Viewer, Android
Studio's APK Analyzer.

**passgen defense**: no hardcoded secrets in source. The only constant
in source that's security-relevant is the signing-cert SHA-256 pin,
which is itself non-secret (the cert is public, the pin merely
identifies "this APK signed by this cert"). Vault data is encrypted
before being written. Master KEK is generated at runtime, never
embedded.

## Test 1: jadx decompile, search for secret-like strings

```bash
jadx --output-dir /tmp/passgen-jadx android/dist/passgen.apk
grep -riE 'password|secret|api[_-]?key|token' /tmp/passgen-jadx/sources \
    | grep -v '\.kt:' | head
```

**Expected**: matches show variable / function / parameter names and
user-facing strings ("Generate password", "Master password", etc.),
but **no actual password values**, no API keys, no tokens, no
embedded credentials.

## Test 2: classes.dex strings extraction

```bash
unzip -p android/dist/passgen.apk classes.dex | strings | sort -u | wc -l
unzip -p android/dist/passgen.apk classes.dex | strings | grep -iE '^[a-zA-Z0-9+/=]{40,}$' | head
```

**Expected**: long base64-looking strings come from BouncyCastle (its
embedded test vectors and constants), AndroidX libraries, and Compose
internals — none are passgen secrets. The expected SHA-256 pin string
appears once (from `Tamper.kt`), and the package name appears multiple
times. Nothing else security-sensitive.

## Test 3: resource strings

```bash
aapt2 dump strings android/dist/passgen.apk | head -50
```

**Expected**: app label, button labels, error messages. No credentials.

## Test 4: certificate digest visible (expected, not a leak)

The constant `EXPECTED_CERT_SHA256 = "5a4e9030c9b4a88f..."` is visible
in the dex. This is intentional and not a secret — it pins the cert,
defeating Lucky Patcher repackaging. The honest claim is "the digest
is visible, but knowing it doesn't help you forge it; you'd need the
private key, which exists only on the build machine."

## Pass/fail

✅ Test 1: no concrete password / secret / API-key strings
✅ Test 2: long base64 strings come from libs, not passgen secrets
✅ Test 3: resource strings are user-facing only
✅ Test 4: cert pin is visible as expected; not a defense weakness
