# 12 — Debugger attach (jdwp / run-as)

**Threat class**: runtime inspection
**Tools**: `adb shell run-as`, jdwp via Android Studio attach, `gdbserver`
attach.

**passgen defense**: `isDebuggable=false` in **both** debug and release
build variants (yes, the debug variant is non-debuggable by design —
we ship sideload-grade APKs). `Debug.isDebuggerConnected()` and
`Debug.waitingForDebugger()` runtime probes at every entry point
(MainActivity onCreate, autofill onFillRequest, IME onCreate, vault
unlock).

## Test 1: run-as fails

```bash
adb shell run-as com.understory.passgen ls /data/data/com.understory.passgen
```

**Expected**: error like `run-as: Package 'com.understory.passgen' is
not debuggable`. The cmd refuses because the manifest declares
`android:debuggable="false"`.

## Test 2: jdwp doesn't list passgen

```bash
adb jdwp
```

**Expected**: passgen's pid does NOT appear in the list of jdwp-enabled
processes. (Other apps marked debuggable WILL appear; that's the
control. passgen specifically is absent.)

## Test 3: aapt2 confirms manifest

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump xmltree \
    android/dist/passgen.apk --file AndroidManifest.xml \
    | grep -iE 'debuggable|jniDebug'
```

**Expected**: no `android:debuggable="true"` attribute; either absent
(which means false by default in modern AGP) or explicitly false.

## Pass/fail

✅ Test 1: run-as is refused
✅ Test 2: passgen is not in `adb jdwp` listing
✅ Test 3: manifest has no debuggable flag set true
