# 16 — Suite cross-attestation (repackaged sibling)

**Threat class**: suite-level tamper / partial-tamper attack
**Tool**: any APK packager + a fresh keystore (apktool + apksigner is the
canonical pair; Lucky Patcher would also work). The attacker's goal is
to install a *sibling* suite app (e.g. a fake aegis) signed by their
own keystore, while the user keeps the genuine passgen. Without
attestation, passgen would treat the fake aegis as legitimate.

**passgen / aegis / firewall defense**:
- Each app calls `SuiteAttestation.verify(applicationContext)` at every
  launch.
- Verify queries every known suite member package via the manifest
  `<queries>` block.
- For each installed sibling, reads its `apkContentsSigners` SHA-256.
- Refuses to run if any installed sibling's cert digest doesn't match
  the suite's pinned digest.
- Symmetric: every installed app verifies every other.
- A tampered sibling drags down every other suite app on the device.

## Setup

You need:
- A test Android device (rooted not required).
- The current `passgen.apk` (legit) installed.
- `apktool` + `apksigner` + a fresh keystore.

## Test 1: only one suite app installed

Verify the baseline: with only passgen on the device, `SuiteAttestation`
finds no siblings and reports `installedSiblings = []`,
`tamperedSiblings = []`, `hardFail = false`.

```bash
adb shell pm list packages | grep -E 'understory\.(passgen|aegis|firewall)'
```

Should show only passgen. Open passgen — it should run normally.

## Test 2: two genuine sibling apps installed

Install the legit `aegis.apk`. Both passgen and aegis should now
mutually attest each other.

```bash
adb install android/dist/aegis.apk
adb shell am start -n com.understory.passgen/.MainActivity
adb shell am start -n com.understory.aegis/.MainActivity
```

Both should run normally. The cross-mesh is active but both certs
match the pin, so no hard-fail.

## Test 3: forge a fake sibling

Re-sign aegis with an attacker keystore:

```bash
cd /tmp
mkdir aegis-forge && cd aegis-forge
cp /path/to/android/dist/aegis.apk fake-aegis.apk
zip -d fake-aegis.apk 'META-INF/*'
keytool -genkeypair -keystore attacker.jks -storepass attacker \
    -keyalg RSA -keysize 2048 -validity 365 \
    -alias attacker -dname "CN=Attacker,O=Test,C=XX" \
    -keypass attacker
apksigner sign --ks attacker.jks --ks-pass pass:attacker fake-aegis.apk
apksigner verify --print-certs fake-aegis.apk | grep "SHA-256"
```

The cert digest will differ from the suite pin
(`5a4e9030c9b4a88fa5fe857b4f86f8d6fd7a9f257ea4c02f541a2490e5083588`).

Uninstall the genuine aegis and install the fake:

```bash
adb uninstall com.understory.aegis
adb install fake-aegis.apk
```

Now open passgen:

```bash
adb shell am start -n com.understory.passgen/.MainActivity
```

**Expected**: passgen briefly launches, `SuiteAttestation.verify()`
detects the fake aegis with its wrong cert, hard-fails, and silently
exits. From the user's perspective: tapping the passgen icon does
nothing. The fake aegis would *also* refuse to run (it would attest
itself, find its own cert is "wrong" relative to the genuine pin
hardcoded in its dex — but realistically the attacker would also patch
the pin in their fake APK, so this test relies on the genuine passgen
still being installed and unmodified).

## Test 4: install fake aegis + fake passgen (full suite tamper)

The realistic full attack: replace BOTH apps with attacker-signed
versions. In that case, the attacker has updated the cert-pin constant
in both APKs to match their own keystore, and `SuiteAttestation`
becomes self-consistent. **This is the inherent limit of client-side
attestation** — once the attacker has full control over both APKs,
they can rewrite both ends of the check. The defense is for the
scenario where the user installs ONE legit app and is later tricked
into installing a malicious sibling. The genuine app's pin
(unmodifiable by the attacker) catches the mismatch.

## Pass/fail

✅ Test 1: solo install runs normally
✅ Test 2: two genuine siblings co-attest, both run normally
✅ Test 3: one genuine + one fake sibling → genuine app hard-fails
   silently
⚠ Test 4: documented limit — full-suite tamper defeats client-side
   attestation, by definition

## Notes for runtime testing

The `<queries>` block grants visibility only for specific package
names. If you ever rename a suite member, `SuiteAttestation`'s
`SUITE_PACKAGES` list and every app's `<queries>` block need updating
in lock-step. The `verifyCertPin` task at the root build doesn't catch
this drift — adding a "queries-list-sync" build check would close that
gap (deferred).
