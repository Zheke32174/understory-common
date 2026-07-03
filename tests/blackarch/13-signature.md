# 13 — APK signature audit

**Threat class**: trust the supply chain
**Tools**: `apksigner verify`, `keytool`, `openssl`.

**passgen defense**: APK Signature Scheme v2 (mandatory on Android 11+
for any new install). All builds from this branch use the same debug
keystore on the build machine, producing the same SHA-256 cert digest:
`5a4e9030c9b4a88fa5fe857b4f86f8d6fd7a9f257ea4c02f541a2490e5083588`.

## Test 1: verify v2 signature

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs \
    android/dist/passgen.apk
```

**Expected output** (key lines):

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
...
Signer #1 certificate SHA-256 digest: 5a4e9030c9b4a88fa5fe857b4f86f8d6fd7a9f257ea4c02f541a2490e5083588
```

If the digest doesn't match `5a4e9030c9b4a88f…`, you have a
differently-signed build — possibly tampered, possibly built on a
different machine. Do not install.

## Test 2: confirm pin in source matches actual

```bash
grep EXPECTED_CERT_SHA256 \
    android/common-security/src/main/java/com/understory/security/Tamper.kt
```

The constant in source must equal the digest reported by `apksigner`.
If they differ, every install hard-fails on launch (the pin check
fails). The build process should fail loudly in this case; until we add
a Gradle task to enforce, this is a manual check.

## Pass/fail

✅ Test 1: v2 verified, expected digest
✅ Test 2: source pin matches actual cert digest
