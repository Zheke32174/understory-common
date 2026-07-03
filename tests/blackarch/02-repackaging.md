# 02 — Lucky Patcher / repackaging / re-signing

**Threat class**: APK repackaging, signature bypass
**Tool**: [Lucky Patcher](https://www.luckypatchers.com/) is the canonical
example, but the underlying technique is generic: any combination of
`apktool d` + edit + `apktool b` + sign-with-attacker-key produces a
modified APK with a different signing certificate.

**passgen defense**:
1. `Tamper.signatureMatches()` reads the installed APK's
   `apkContentsSigners` and compares the SHA-256 against the hardcoded
   `EXPECTED_CERT_SHA256` in `Tamper.kt`. Any mismatch hard-fails.
2. `Tamper.luckyPatcherInstalled()` enumerates known patcher package
   names (via the manifest `<queries>` block — no `QUERY_ALL_PACKAGES`
   permission). If Lucky Patcher (or any of its aliases) is installed,
   hard-fail.
3. `Tamper.installedByPatcher()` reads `getInstallSourceInfo().installingPackageName`
   and rejects if the installer is a known patcher.

The signature pin is the load-bearing defense; the package / installer
checks are belt-and-suspenders.

## Setup

You need:
- The current `passgen.apk` (md5 of any current build is in the repo's
  push commit messages)
- `apktool` installed locally
- A debug keystore other than the one this APK was built with (any
  Android Studio-generated debug keystore on a different machine works)
- The phone you're testing on

## Test 1: verify the unmodified build runs

Confirm baseline:

```bash
adb install -r android/dist/passgen.apk
adb shell am start -n com.understory.passgen/.MainActivity
```

**Expected**: passgen launches normally, all three modes (clipboard,
autofill, keyboard) work.

## Test 2: re-sign with a different key

Strip the original signature and re-sign with a different key:

```bash
cd /tmp
cp /path/to/android/dist/passgen.apk passgen-original.apk
# Remove META-INF (signature directory)
zip -d passgen-original.apk 'META-INF/*'
# Re-sign with a new debug key
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android passgen-original.apk
mv passgen-original.apk passgen-resigned.apk
apksigner verify --print-certs passgen-resigned.apk
```

The cert digest in the output should differ from
`5a4e9030c9b4a88fa5fe857b4f86f8d6fd7a9f257ea4c02f541a2490e5083588`.

Install:

```bash
adb uninstall com.understory.passgen  # original must be gone first
adb install passgen-resigned.apk
adb shell am start -n com.understory.passgen/.MainActivity
```

**Expected**:
- Activity briefly starts (super.onCreate runs)
- `Tamper.signatureMatches()` returns false (digest doesn't match the pin)
- `hardFail` is true
- `finishAndRemoveTask()` fires
- App icon never shows in the launcher (excludeFromRecents)
- From the user's perspective: tapping the icon does nothing

To confirm via logcat:

```bash
adb logcat | grep -i passgen
# You'll see the activity start and immediately end.
# No password generation, no UI, no toast, no crash.
```

**Pass criterion**: the resigned APK installs but produces no UI on
launch. If you can interact with it normally, the signature pin failed
to fire — investigate.

## Test 3: full apktool decompile + recompile + re-sign

The most realistic Lucky Patcher attack: decompile, modify code, recompile.

```bash
cd /tmp
apktool d passgen.apk -o passgen-extracted
# Make any visible modification — e.g., change a string in
# passgen-extracted/res/values/strings.xml
sed -i 's/passgen/MODIFIED/' passgen-extracted/res/values/strings.xml
# Recompile
apktool b passgen-extracted -o passgen-modified.apk
# Sign
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android passgen-modified.apk
# Verify it's a different cert
apksigner verify --print-certs passgen-modified.apk
```

Install and launch as above. Same expected outcome: hard-fail, no UI.

This proves the signature pin is the defense, not just the file integrity
of the original APK.

## Test 4: install Lucky Patcher itself on the device

Note: Lucky Patcher's APK is widely available but distributing it is its
own legal mess. For this test, get it from Lucky Patcher's official site
on a clean test device.

```bash
adb install LuckyPatcher.apk
# Now install the unmodified passgen
adb install -r android/dist/passgen.apk
adb shell am start -n com.understory.passgen/.MainActivity
```

**Expected**: passgen detects Lucky Patcher in the installed packages
list (via `<queries>` granting visibility to `com.chelpus.lackypatch`
and aliases), and hard-fails on launch even though the signature is
still valid.

To confirm: `adb shell pm list packages | grep -iE 'lackypatch|luckypatcher|lp'` — should show Lucky Patcher's package. Then passgen should refuse to run.

## Test 5: install via Lucky Patcher's installer

If Lucky Patcher is the installing package, `getInstallSourceInfo()`
returns its package name. Use Lucky Patcher's "install" feature on the
phone to install passgen. After install, run passgen.

**Expected**: hard-fail because `installedByPatcher()` returns true.

## What this test does NOT cover

- An attacker who patches *the EXPECTED_CERT_SHA256 constant in Tamper.kt
  itself*. With sufficient effort, the attacker can find the constant in
  the dex, replace it with their own cert's digest, recompile, sign with
  their key, and the check passes. Defense at this level is
  intrinsically arms-race; ProGuard / R8 obfuscation in release builds
  raises the cost.
- An attacker who patches out `Tamper.check()` entirely (replaces the
  method body with `return Report(true, ...)`). Same arms race; same
  defense layer.
- The attacker's downstream goal — even a successful repackage doesn't
  give them passgen's vault contents (those are encrypted at rest under
  the user's Keystore key, which doesn't follow a repackaged APK).

The realistic claim is: **Lucky Patcher in its standard one-click usage
fails against passgen.** Determined attackers willing to dex-edit our
Tamper class can defeat this. That's the inherent ceiling for client-
side tamper detection on a platform without verified boot for arbitrary
APKs.

## Pass/fail criteria

✅ Test 1: original APK runs normally
✅ Test 2: re-signed APK fails to launch UI
✅ Test 3: full apktool round-trip APK fails to launch UI
✅ Test 4: passgen with unmodified APK refuses to run when Lucky Patcher is installed
✅ Test 5: passgen refuses to run when installed via Lucky Patcher's installer
