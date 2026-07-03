# 15 — Process memory dump

**Threat class**: forensic recovery of in-process secrets
**Tools**: `gcore` (gdb), `/proc/PID/mem` direct read, Frida memory
dump scripts. **All require root on the device.**

**passgen defense**:
- `CharArray` for password material; wiped after use
- Short secret lifetimes — generate, encrypt, wipe inside one stack frame
- BiometricPrompt-gated just-in-time decryption — vault KEK exists in
  memory only between BiometricPrompt success and `vault.lock()`
- Lock on background — the moment user navigates away,
  `finishAndRemoveTask()` fires, `vault.lock()` wipes the KEK and clears
  the entries list
- Strings that DO exist inside `contents.entries` get GC'd when
  `lock()` replaces the list

The honest scope: an attacker with **root access on an unlocked phone
where you've just unlocked the vault** can dump memory and find the KEK
and any decrypted entries currently held. This is the inherent limit of
a per-device security model. It's also the threat model where any
password manager is in trouble.

## Setup

A rooted test device (Magisk or rooted-emulator). passgen installed.

## Test 1: locked vault has no plaintext entries

1. Open passgen, set up a vault, add a few entries, lock the vault
2. Bring up a root shell: `adb shell su`
3. Find passgen's PID: `pidof com.understory.passgen` (it may not even
   be running — Android killed the process when the activity finished)
4. If the process IS still running, dump:

```bash
PID=$(pidof com.understory.passgen)
gcore -o /sdcard/passgen-core $PID
strings -n 12 /sdcard/passgen-core.* | grep -iE 'master|password' | head
```

**Expected**: no plaintext entry data. The vault is encrypted at rest;
when locked, the KEK is wiped; the entries list is replaced with empty.
What's findable in memory is constants, framework strings, lib code —
nothing user-specific.

## Test 2: unlocked vault holds entries (acknowledged)

Repeat with the vault unlocked, currently viewing the entry list:

```bash
PID=$(pidof com.understory.passgen)
gcore -o /sdcard/passgen-core $PID
strings -n 12 /sdcard/passgen-core.* | grep "passgen vault master" -A 2
```

**Expected**: you'll find the master entry's title, username, and
possibly the password (base64-encoded). This is the documented
limitation: while the vault is unlocked AND held in memory, its
contents are decrypted in process. Background the app → contents
wiped within seconds.

## Test 3: reveal window holds the password (acknowledged)

While viewing an entry and reveal is active (10-second window), the
plaintext password IS in process memory rendered into the Compose
text node. After 10s auto-hide, the Compose node is recomposed without
the value; the prior string becomes GC-eligible.

This is the inherent limit. If your threat model includes "rooted
attacker pulling memory at the exact moment I'm viewing a password,"
no password manager survives.

## Pass/fail

✅ Test 1: locked vault → no plaintext findable in memory
⚠ Test 2: unlocked vault → entries findable; documented, expected
⚠ Test 3: active reveal → that password findable for 10s; documented
