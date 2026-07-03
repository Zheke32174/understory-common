# tests/blackarch — verification runbooks

This directory contains runbooks and scripts for verifying passgen's
defenses against userspace offensive tools (the BlackArch toolkit).

Read [DEFENSE_MATRIX.md](./DEFENSE_MATRIX.md) for the headline claims and
status. Each row in that matrix links to a runbook here.

## Quick start

If you just want to run all the **scriptable** tests against the current
APK in `android/dist/passgen.apk`:

```bash
cd android/tests/blackarch/scripts
./run-all-scriptable.sh
```

That runs the static checks: signature, permissions, backup attempt,
string extraction, manifest inspection. About 30 seconds. Doesn't require
a connected device for most of it.

The interactive tests (Frida, Lucky Patcher repackage, mitmproxy, screen
capture, tap-jack overlay, accessibility service, memory dump) need a
real Android device with USB debugging enabled, plus the BlackArch tool
in question. Each has its own walkthrough.

## What you need

**Always:**
- An Android phone with the latest passgen.apk installed
- `adb` from `platform-tools`
- `aapt2` and `apksigner` from `build-tools`

**For specific tests:**
- Frida (`pip install frida-tools` + frida-server on a rooted test device — runbook 01)
- Lucky Patcher APK + `apktool` + your own debug keystore (runbook 02)
- LSPosed framework on a rooted/Magisk test device (runbook 03)
- mitmproxy on your laptop, phone proxied to it (runbook 04)
- scrcpy or any screen-capture tool (runbook 05)
- A test overlay app — small one in `tools/overlay-tester/` (runbook 06)
- A test accessibility service — small one in `tools/a11y-tester/` (runbook 07)
- (no extras — Gboard / Samsung Keyboard clipboard panels are observable directly — runbook 08)
- Just `adb` (runbook 09)
- `jadx` / `apktool` / GNU coreutils (runbook 10)
- Just `aapt2` (runbook 11)
- Just `adb` (runbook 12)
- Just `apksigner` (runbook 13)
- `unzip` + GNU coreutils (runbook 14)
- `gcore` from gdb, root on a test device, or Frida memory-dump (runbook 15)

## Honest scope

These runbooks verify what passgen claims. They are not a comprehensive
mobile security audit — they're targeted at specific BlackArch-class
tools and the specific defense mechanisms passgen ships. A real audit
would cover threats not in this matrix (timing attacks, fault injection,
TEE soundness, etc.) — those are out of scope for this suite by design.

## Reporting results

If you run a runbook and the actual outcome diverges from the expected
outcome, that's a defect. File against the passgen branch with:

- Runbook number
- Device fingerprint (`adb shell getprop ro.product.model`, `getprop ro.build.version.release`)
- Commands you ran
- Output you got
- Output the runbook said to expect

The matrix gets updated from ⏳ to ✅ when a runbook is verified on a
real device. Until then, claims are aspirational.
