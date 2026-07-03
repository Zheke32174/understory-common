# 08 — Keyboard clipboard panel scraping

**Threat class**: exfil via clipboard-history surface
**Tools**: Gboard's clipboard chip, Samsung Keyboard's clipboard panel,
SwiftKey clipboard sync, third-party clipboard managers.

**passgen defense**:
- `ClipDescription.EXTRA_IS_SENSITIVE` on every clipboard write — Gboard
  on Android 13+ honors this and skips the entry
- Auto-clear after configurable timeout (default 30s)
- **Honest known leak**: Samsung Keyboard's clipboard panel does NOT
  honor the sensitive flag (verified during development — the user
  observed it). We document this in the app's UI banner and recommend
  the IME / autofill paths on Samsung devices.

## Setup

A phone with Gboard installed and active, AND a Samsung phone with
Samsung Keyboard active. (Or one Samsung where you can switch keyboards
to test both.)

## Test 1: Gboard skips the entry

On a Pixel or any phone using Gboard:

1. Open passgen, generate via clipboard mode (Generate & Copy)
2. Open any app with a text field
3. Long-press the field; observe the clipboard suggestion above the
   keyboard

**Expected**: Gboard's clipboard chip / suggestion does NOT show the
copied passgen value. Standard paste (long-press → Paste) still works
because the clipboard itself contains the value.

## Test 2: Samsung Keyboard retains (known leak)

On a Samsung phone with Samsung Keyboard:

1. Open passgen, generate via clipboard mode
2. Open any app with a text field
3. Open Samsung Keyboard's clipboard panel (the icon in the toolbar)

**Expected**: the entry IS visible in the panel. This is the
documented limitation; the in-app UI calls it out under the clipboard
section. The fix is to not use clipboard mode on Samsung — use
autofill or IME instead.

## Test 3: auto-clear after timeout

Generate via clipboard mode with auto-clear set to 30s. Wait 35s. Try
to paste in another app.

**Expected**: the clipboard either contains nothing or contains a
non-passgen value (whatever was on it before the auto-clear). Note: the
keyboard's clipboard PANEL on Samsung will still retain the historical
entry — auto-clear addresses the system clipboard, not the keyboard's
private store.

## Pass/fail

✅ Test 1: Gboard suggestion does not include the passgen value
⚠ Test 2: Samsung Keyboard panel retains; documented limitation
✅ Test 3: system clipboard auto-clears in the configured window
