# passgen — review notes

Notes from a code-review pass before the firewall app gets built. Listed
roughly by severity (top = most important). None of these block shipping
v1; they're items to handle when there's a free moment.

## Correctness / safety

1. **Hardcoded signing-cert SHA-256** — `Tamper.kt:35`
   `EXPECTED_CERT_SHA256` must match the actual cert of every shipped
   APK build. If it doesn't, every install hard-fails silently. Confirm
   the value matches your release keystore and document the rotation
   process (release-notes step: "if the signing key changes, update
   this constant"). Consider a Gradle task that fails the build if the
   constant doesn't match the keystore.

2. **`signatureMatches` accepts historical certs** — `Tamper.kt:86`
   Uses `signingCertificateHistory` when not multi-signer. That allows
   *rotated-out* certs to still pass — which is the wrong behaviour if
   a cert was rotated *because* of compromise. Switch to
   `apkContentsSigners` exclusively for the strictest check.

3. **Vault-save has a small "no vault" window** — `Vault.kt:325–326`
   `UnlockedVault.save()` calls `target.delete()` then
   `tmp.renameTo(target)`. Process death between those two calls →
   vault gone. The other path (`Vault.writeFile()`, lines 212–232)
   does rename-first with delete-then-rename only as a fallback —
   that's the safer order. Refactor both call sites to a single
   shared helper using the safer order.

## Performance / UX

4. **`Tamper.check()` is called from hot paths**
   Every `onFillRequest`, every IME `onStartInput`, every secure click
   re-runs all 7 hook-class probes, reads `/proc/self/maps`, and
   queries 9 patcher packages. Memoize for ~5s with lifecycle-event
   invalidation (e.g. invalidate on `onResume`, `onConfigurationChanged`,
   `ACTION_PACKAGE_ADDED` broadcast).

5. **Reveal-lock uses full vault-grade Argon2** — `Vault.kt:289`
   64 MiB / 3 iter on every "show password" toggle is ~200ms+ on
   midrange devices. Vault is already unlocked at this point, so
   reveal-lock is a UX gate, not a key derivation. Pick lighter
   params (e.g. 16 MiB / 2 iter) for the reveal-lock specifically;
   keep full strength for actual vault unlock.

## Hardening

6. **IME `ObscuredTouchGate` only filters DOWN** — `PassgenInputMethodService.kt:194`
   The Compose `SecureButton` correctly also re-checks `ACTION_MOVE`
   (overlays that flicker between DOWN and UP slip through a DOWN-only
   filter). Mirror the Compose logic here.

7. **HTML5 password detection misses `autocomplete="*-password"`** — `PassgenAutofillService.kt:147`
   Only matches `<input type="password">`. Modern browsers/webviews
   increasingly use `autocomplete="new-password"` /
   `autocomplete="current-password"` on `<input type="text">`. Some
   webview-based logins won't get suggestions today.

## Architecture (longer-horizon)

8. **`VaultEntry.password` is `String`** — `Vault.kt:43`
   The doc-comment in `UnlockedVault.lock()` honestly notes this is
   the strongest in-process erasure available without JNI. If the
   threat model wants true wipeability, switch to `CharArray`
   end-to-end and serialize via `JSONStringer` with manual
   char-by-char writes — never going through `JSONObject.put(String)`.
   Significant refactor; flag for Phase 2.

9. **Suite-wide: extract `:common-security` module**
   When the firewall (and later apps) need `Tamper`, `A11yProbe`,
   `DeviceProfile`, `SecureButton`, FLAG_SECURE wiring, etc., they
   should consume a shared module — not re-implement. Otherwise the
   suite's hardening will diverge: a fix lands in one app and not the
   others. Cheap to extract while there's one consumer; real refactor
   later.

## Not bugs — just observations

- The `tools:node="remove"` permission strip is great, including the
  uncommon ones (SATELLITE_COMMUNICATION, BACKGROUND_CAMERA). Keep
  this pattern as the suite default.
- `setHideOverlayWindows(true)` + FLAG_SECURE on the IME *window*
  (not just the activity) + `setRecentsScreenshotEnabled(false)` is a
  thorough belt-and-braces stack that I haven't seen elsewhere.
- `isDebuggable=false` in the *debug* variant is the right call for a
  sideload-installable security tool. Worth a one-line comment in
  `build.gradle.kts` so future-you doesn't "fix" it.
- The two-secret KEK derivation (Argon2id output XOR Keystore aux,
  then SHA-256 mixed) makes a stolen vault.bin useless without the
  device's hardware-backed wrap key. Document this in the README's
  "what's persisted" section so users know the threat model.

## Open questions for the maintainer

- Is `EXPECTED_CERT_SHA256` the real cert hash for the build keystore,
  or a placeholder? (See item 1.)
- Is TOTP / 2FA planned as a separate sibling app, or living inside
  the vault as another entry-type field? Affects the rule-schema for
  the firewall's per-app rules and whether the suite shares an
  unlock-state IPC.
