# 05 — Screen capture / screen recording

**Threat class**: visual exfiltration
**Tools**: `adb exec-out screencap`, scrcpy, third-party screen-record
apps, system screenshot, Samsung's smart capture, casting, external
display.

**passgen defense**:
- `WindowManager.LayoutParams.FLAG_SECURE` on every activity in the app
  (MainActivity, VaultActivity, GenerateAndFillActivity)
- FLAG_SECURE on the IME service's window too
- `setRecentsScreenshotEnabled(false)` on API 33+
- `setHideOverlayWindows(true)` on API 31+

FLAG_SECURE is the load-bearing defense; it tells the framebuffer
compositor to mark the surface as DRM-style protected, which propagates
to all capture mechanisms.

## Test 1: adb screencap

```bash
# Open passgen on the phone, navigate to any screen
adb exec-out screencap -p > /tmp/passgen-screen.png
```

**Expected**: the resulting PNG is solid black where passgen's surface
is (status bar may be visible since that's drawn by the system, not
passgen).

## Test 2: scrcpy

```bash
scrcpy
# Then on the phone, open passgen
```

**Expected**: passgen's window appears as a solid black rectangle in
the scrcpy mirror view. The area outside passgen (status bar, nav bar)
renders normally.

## Test 3: system screenshot

On the phone, while passgen is open, trigger the system screenshot:
- Power + Volume Down (most devices)
- Three-finger swipe (Samsung)
- "Take screenshot" from the recents button

**Expected**: a system toast / message appears: "Can't take screenshot
due to security policy." Or the screenshot succeeds visually but the
captured image is black where passgen was.

## Test 4: screen recording

Use the system screen-recorder (Samsung One UI's, Pixel's, etc.).
Record while interacting with passgen.

**Expected**: the recording shows passgen's area as black frames.

## Test 5: external display / cast

Cast to a Chromecast or external display via Smart View / Cast.

**Expected**: passgen's surface does not appear on the external display
(FLAG_SECURE blocks output to non-secure displays).

## Test 6: recents thumbnail

Open passgen, hit the recents/overview button.

**Expected**: passgen's recents card shows a generic icon / placeholder
instead of the live screenshot. (`setRecentsScreenshotEnabled(false)`
on API 33+ enforces this explicitly; FLAG_SECURE alone usually suffices
on most OEM skins, but Samsung historically had quirks here.)

## Test 7: IME screen capture

Switch to the passgen keyboard on a password field. While the keyboard
is up, attempt `adb exec-out screencap`.

**Expected**: keyboard area is also black (FLAG_SECURE on the IME
window).

## Pass/fail

All seven tests should produce black surfaces / blocked captures. Any
test that produces a readable screenshot of passgen's UI is a defect.
