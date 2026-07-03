# 01 — Frida injection

**Threat class**: dynamic instrumentation / hooking
**Tool**: [Frida](https://frida.re) — a runtime-injection toolkit used to hook
into running processes, intercept method calls, and rewrite app behavior.
Standard tool in the BlackArch reverse-engineering category.

**passgen defense**: `Tamper.fridaInjected()` in `Tamper.kt` reads
`/proc/self/maps` at every entry point (MainActivity onCreate, autofill
onFillRequest, IME onCreateInputView, vault unlock) and looks for the
canonical Frida markers: `frida-agent`, `frida-gadget`, `gum-js-loop`,
`linjector`. If any line matches, `hardFail` returns true and the entry
silently exits.

## Setup

You need a **separate test device** that's rooted. Frida requires running
`frida-server` as root on the device to attach to other processes. Do not
use your daily-driver phone for this — running frida-server creates
significant attack surface.

On the test device:

```bash
# Push frida-server matching your device architecture and Frida version
# Get from https://github.com/frida/frida/releases
adb push frida-server-16.x.x-android-arm64 /data/local/tmp/frida-server
adb shell chmod 755 /data/local/tmp/frida-server
adb shell "su -c '/data/local/tmp/frida-server &'"
```

On your laptop:

```bash
pip install frida-tools
frida-ps -U  # should list device processes; confirms frida-server is running
```

Install the latest `passgen.apk` on the test device.

## Test 1: passive observation (no hooks)

Try to attach Frida to passgen without injecting any hooks:

```bash
# Cold-start passgen with Frida watching for the process
frida -U -f com.understory.passgen --no-pause
```

**Expected outcome**: passgen's MainActivity launches, `Tamper.check()`
runs, `fridaInjected()` returns true (because `/proc/self/maps` now
contains frida-agent loaded by `frida -f`'s spawn injection), and
`hardFail` is true. The activity calls `finishAndRemoveTask()` and
exits silently.

**Frida console output**: Frida will show the process spawning then
exiting almost immediately. Something like:

```
Spawning `com.understory.passgen`...
Spawned `com.understory.passgen`. Resuming main thread!
[Pixel 7::com.understory.passgen]-> Process terminated
```

**If the activity stays open or you can interact with it**: the defense
failed. Capture `/proc/PID/maps` while passgen is running and see what
needles are present. Compare with the strings list in `Tamper.fridaInjected()`.

## Test 2: late attach

Launch passgen normally first, then attach Frida after-the-fact:

```bash
# Launch passgen by tapping its icon. Confirm the UI is up.
# Then from your laptop:
frida -U passgen
```

**Expected outcome**: Frida attaches successfully (this part the OS
allows), but the next time `Tamper.check()` runs (e.g., the user taps
the vault button to open VaultActivity), `fridaInjected()` returns true
and that activity refuses to start. The currently-open MainActivity
remains because the check ran at *its* onCreate before Frida was
attached. This is acknowledged: **once an activity is past the gate,
late-attached Frida can hook it, but it cannot move the user to any new
gated entry point** (vault, autofill fill, IME generate).

If we want stricter behavior — re-running Tamper on every UI tap — we
can wire `Tamper.invalidate()` to onResume. Currently we cache for 5s
to keep autofill/IME responsive. The trade-off is documented and
acceptable.

## Test 3: Tamper.invalidate timing

If Stage 2D wires `Tamper.invalidate()` to `onResume`, late-attached
Frida should be detected within one resume cycle:

```bash
# Same setup: launch passgen normally, then attach Frida.
frida -U passgen
# Push the app to recents, then resume it.
adb shell input keyevent KEYCODE_RECENT_APPS
adb shell input keyevent KEYCODE_RECENT_APPS  # back into the app
```

**Expected (after Stage 2D wires onResume invalidation)**: passgen detects
the late attach on resume, hard-fails on the next tamper check.

## What this test does NOT cover

- Frida-gadget *embedded into a repackaged APK* — that's covered by test 02
  (Lucky Patcher repackaging) because the APK's signature changes
- Anti-anti-Frida tooling (`fridare`, `frida-stalker` with bypass scripts) —
  these can defeat naive `/proc/self/maps` checks. Our claim is "raises
  the bar against unsophisticated Frida usage." Sophisticated attackers
  who can rewrite their gadget signatures or unmap before /proc reads
  can defeat this. The honest scope.
- Frida on iOS — n/a, Android only

## Pass/fail criteria

✅ Test 1 produces the "Process terminated" outcome above
✅ Test 2 lets you launch but blocks navigation into vault / autofill / IME
⏳ Test 3 requires Stage 2D's onResume invalidation; defer until landed
