# Samsung One UI quirks

Things that bite the suite specifically on Samsung One UI (and sometimes
on other OEM skins). The shared pattern: Samsung adds system overlays,
gestures, and edge UI on top of stock Android. From a `MotionEvent`
perspective these are indistinguishable from a hostile overlay, so any
Android API gated on "is this touch obscured" treats legitimate Samsung
UI as adversarial and silently drops the touch.

This document is a checklist to walk before changing UI in any app.
Going through it once on a touch / Activity / Service change has saved
us iterations every time we've actually done it; we keep adding to it
when something new bites us.

## Known quirks

### `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` is the rule, not the exception

Samsung Edge Panel, swipe-from-edge gestures, and the One UI navigation
overlays all set `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED` on
touches that pass through them. This is the documented Android signal
for "another window is partially over our window" — exactly what you'd
want as a tap-jacking defense — but on Samsung it triggers from
legitimate system UI, not just hostile overlays.

**Symptom**: tapping a `SecureButton` or `secureClickable`-protected
control does nothing. No log, no crash. The `pointerInteropFilter`
silently sets `blocked = true` on `ACTION_DOWN` and the `onClick` early-
returns.

**Real cases this has bitten us**:
- Firewall toggle Switch (lifted decor `filterTouchesWhenObscured`,
  rewrote AppRow Switch to passive)
- Backups + vault-folder file-pick buttons ("Pick a file", "Pick output
  location") silently never opening the SAF picker
- Vault-folder Export button (same root cause)

**Rule going forward**:
- `SecureButton` / `SecureOutlinedButton` / `secureClickable` go ONLY on
  irreversible destructive paths: Delete confirms, Wipe, Reveal recovery
  key, Encrypt-with-secret, Decrypt-with-secret. Anywhere a tap-jacked
  click could destroy data or expose a secret.
- Plain `Button` / `OutlinedButton` / `Modifier.clickable` for
  everything else, including SAF picker launches (the system picker has
  its own anti-overlay defenses), navigation buttons, settings entries.
- The system consent dialog (`VpnService.prepare()` consent, biometric
  prompt) is rendered by Android with platform-level anti-overlay
  protection — we don't need to add ours on top.

### `filterTouchesWhenObscured` on the decor view filters legitimate taps

Same root cause as above, but at a different layer. Setting
`window.decorView.filterTouchesWhenObscured = true` is a global "drop
any touch that's obscured" filter. Under Samsung Edge Panel this drops
legitimate user taps across the whole window.

**Rule**: do NOT set `filterTouchesWhenObscured = true` on the activity
decor view. We rely on `FLAG_SECURE` for screenshot/recording defense
and on `SecureButton` per-control wrappers for the destructive paths.

**Already lifted from**: firewall, vault-folder. Backups never had it.

### `foregroundServiceType="systemExempted"` is silently rejected on Android 14+

`systemExempted` is reserved for system-level apps. Android 14
(`UPSIDE_DOWN_CAKE`) silently rejects `startForeground()` calls from
non-system apps with this type — no exception, no `MissingForegroundServiceTypeException`,
the call simply doesn't promote the service to foreground state. The
service then lives briefly without foreground status and gets killed
within seconds.

**Symptom**: VPN toggle in firewall fired the consent dialog, user
consented, but no VPN tun ever appeared. Service was started, then
killed, with no obvious cause.

**Fix**: VPN-class services use `foregroundServiceType="specialUse"`
plus a matching `<property
android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
android:value="vpn" />` declaration. For Android 13- the no-type
overload of `startForeground(id, notification)` is fine; for Android 14+
you must pass the explicit type.

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(NOTIF_ID, buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
} else {
    startForeground(NOTIF_ID, buildNotification())
}
```

### VPN slot is single-tenant; another app preempts ours silently

Not Samsung-specific but bites the firewall: Android allows exactly one
active `VpnService` at a time, and the most-recent `VpnService.prepare()`
caller wins. When the user enables Proton (or any other VPN), our
firewall's tun is revoked, `onRevoke()` fires, and the user is left
with stale UI claiming the firewall is on while Proton holds the slot.

**Rule**: in `onRevoke()`, persist `vpnRequested = false` and a sticky
`vpnPreempted = true` flag BEFORE tearing down. The UI's `ON_START`
re-read renders an honest "preempted" banner with a one-tap re-enable
that walks back through the consent dialog. Never let UI silently lie
about active state.

### Switch double-fire: `onCheckedChange` + clickable parent

When a Material `Switch` has `onCheckedChange = { ... }` AND its parent
Row has `secureClickable { ... }` calling the same handler, tapping the
Switch fires both — handler runs twice, state ends up at original after
the second toggle, and during the recomposition cascade neighbouring
rows briefly visualize the wrong state. Looked on-device like
"toggling one app turned all of them on."

**Rule**: when a Row is clickable as a unit, the Switch is a passive
indicator: `Switch(checked = state, onCheckedChange = null)`. Single
click path through the parent.

### Antivirus / parsing / I/O on the main thread will hang One UI

Samsung One UI's threading model is more aggressive than AOSP about
ANR'ing the main thread on long synchronous I/O. The antivirus's
`auditInstalled()` was originally main-thread; on Samsung it visibly
hung the UI for ~3-5 seconds before showing results.

**Rule**: any work that walks `PackageManager`, hits the file system,
runs crypto, or otherwise takes >100ms goes through `Dispatchers.IO`
inside a `rememberCoroutineScope().launch { withContext(Dispatchers.IO) { ... } }`.
Compose state updates back on the main dispatcher.

### Window flags on splash → main transition

`window.setFlags(FLAG_SECURE, FLAG_SECURE)` should be set BEFORE
`setContent { }`. If set after, there's a one-frame window where the
content renders without `FLAG_SECURE`, which Samsung's screenshot /
edge-screen-capture features can grab.

**Rule**: `window.setFlags(...)` and `setHideOverlayWindows(true)` go in
`initialize()` (or wherever you do non-Compose pre-setContent work),
before any `setContent {}` call.

## Pre-flight checklist (run before any UI change)

When changing anything that involves a touch path, a foreground
service, a Switch, or a SAF picker — walk this list:

- [ ] Is the action irreversible / does it expose a secret? Yes →
      `SecureButton`. No → plain `Button` or `OutlinedButton`.
- [ ] Is there a Switch nested inside a clickable Row? Make the Switch
      passive (`onCheckedChange = null`).
- [ ] Is this a foreground service start? Pass an explicit
      `foregroundServiceType` matching the manifest declaration on
      Android 14+.
- [ ] Is `filterTouchesWhenObscured = true` set on the decor view?
      Remove it.
- [ ] Is any work synchronous on the main thread that could exceed
      ~100ms? Move to `Dispatchers.IO`.
- [ ] Is `FLAG_SECURE` set before the first `setContent {}`?
- [ ] If the action holds a system-singleton resource (VPN, biometric),
      is there a "preempted by another app" recovery path?

When something new bites us, add it here.
