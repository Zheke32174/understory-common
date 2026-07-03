# 03 — Xposed / LSPosed hooking

**Threat class**: runtime hooking framework
**Tools**: [LSPosed](https://github.com/LSPosed/LSPosed) (active),
EdXposed (legacy), original Xposed Framework (legacy). Standard reverse-
engineering toolkit.

**passgen defense**: `Tamper.hookFrameworkLoaded()` probes for the
canonical bridge classes via `Class.forName`:
- `de.robv.android.xposed.XposedBridge`
- `de.robv.android.xposed.XC_MethodHook`
- `de.robv.android.xposed.IXposedHookLoadPackage`
- `org.lsposed.lspd.core.Main`
- `org.lsposed.lspd.nativebridge.Yahfa`
- `io.github.lsposed.lspd.LSPApplication`
- `io.github.lsposed.lspd.core.Main`

If any class is loadable in our process, hard-fail.

## Setup

LSPosed requires Magisk root. Use a separate test device.

1. Root the test device with Magisk
2. Install LSPosed via Magisk modules
3. Reboot
4. Install passgen.apk

## Test 1: LSPosed installed but passgen not in scope

Don't add passgen to LSPosed's targeted apps. Just have LSPosed running
on the device.

**Expected**: passgen launches normally. The LSPosed framework being
*present* on the device doesn't load Xposed classes into passgen's
process unless passgen is in scope.

## Test 2: passgen added to LSPosed scope

Open LSPosed Manager, find any installed Xposed module, add passgen to
its scope. Reboot. Launch passgen.

**Expected**: hard-fail at every entry point. Bridge classes are
loadable in passgen's process now, `hookFrameworkLoaded()` returns true.

## Test 3: hide-modules countermeasures

Some Xposed users run modules like Shamiko or "hide my apps from
modules" tools. These can hide LSPosed from detection.

**Expected**: with such hiding active, our class-probe defense may be
bypassed. This is the arms-race ceiling. Honest scope.

## Pass/fail

✅ Test 1: passgen runs normally with idle LSPosed
✅ Test 2: passgen hard-fails when LSPosed scopes it
⚠ Test 3: hide-modules can defeat the probe; documented limitation
