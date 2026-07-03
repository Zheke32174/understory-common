# 06 — Tap-jack / clickjacking via overlay

**Threat class**: UI redress
**Tool**: any app with `SYSTEM_ALERT_WINDOW` permission that draws a
transparent or partially-obscuring view over passgen, capturing taps
intended for passgen and routing them to attacker code (or relaying them
through with state changes the user doesn't intend).

**passgen defense**:
- `Window.setHideOverlayWindows(true)` on API 31+ — system removes
  overlays from over our window when called
- `View.filterTouchesWhenObscured = true` on the Compose root —
  rejects events with `FLAG_WINDOW_IS_OBSCURED` set
- `SecureButton` / `SecureOutlinedButton` (Compose) — re-checks
  `FLAG_WINDOW_IS_OBSCURED` *and* `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` on
  both ACTION_DOWN and ACTION_MOVE; click suppressed if either is set
- `ObscuredTouchGate` (IME) — same logic in the keyboard view

The Compose-level defense is the strongest; it catches partial
overlays that the native FLAG check misses.

## Setup

Install a test overlay app. The simplest is to write a minimal one or
use a public one with the alert-window permission. A short test app
template (Kotlin, single activity, draws a tiny semi-transparent button
overlay):

```kotlin
class OverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        val view = TextView(this).apply {
            text = "OVERLAY"
            setBackgroundColor(0x80FF0000.toInt())  // semi-transparent red
        }
        val params = WindowManager.LayoutParams(
            300, 100,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.CENTER
        getSystemService(WindowManager::class.java).addView(view, params)
    }
}
```

Build, install, grant overlay permission.

## Test 1: passgen blocks the overlay (API 31+)

With the overlay app running and its overlay visible, open passgen.

**Expected**: as soon as passgen's MainActivity comes to the front,
`setHideOverlayWindows(true)` causes the system to *hide* the overlay
view. The overlay app's red box disappears while passgen is in front.
Returns when passgen is backgrounded.

If you see the overlay still visible over passgen → defense failed for
that path.

## Test 2: secure button rejects obscured tap

If for any reason `setHideOverlayWindows` doesn't apply (older API,
OEM bug), the SecureButton should still reject the tap.

To test: temporarily edit MainActivity to NOT call
`window.setHideOverlayWindows(true)` (just for this test), build,
install, run with the overlay app active.

Tap the "Generate & Copy" button through the overlay. The tap should
register on passgen's button visually (button highlights) but no toast
should appear and no clipboard write should happen — the click is
suppressed by the SecureButton's obscured-touch check.

To confirm: `adb shell service call clipboard 1 i32 0` returns nothing
new in the clipboard.

## Test 3: IME tap-jack

Switch to passgen IME on a password field. Have the overlay app
covering part of the keyboard.

**Expected**: tapping "Generate & insert password" through the overlay
does nothing (the `ObscuredTouchGate` rejects on DOWN and MOVE).

## Pass/fail

✅ Test 1: overlay disappears when passgen comes to front
✅ Test 2: with overlay active and `setHideOverlayWindows` disabled, the
   button still rejects taps
✅ Test 3: IME button rejects obscured taps
