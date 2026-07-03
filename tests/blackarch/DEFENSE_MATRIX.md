# passgen — defense matrix

A specific accounting of what passgen defends against, what it doesn't, and
how to verify each claim. This is positioning *as evidence*: every defense
in the matrix has a test that you can run against a real install on a real
device, and a documented expected outcome.

The naming "anti-BlackArch" is shorthand for "tested against the userspace
offensive toolkit." We don't claim defense against kernel exploits, hardware
attacks, or vendor-firmware backdoors — those need OS-level hardening
(GrapheneOS) or hardware separation, neither of which we are.

## Conventions

- ✅ = defense is in place AND has been verified by the linked test
- ✅ (host) = the part testable from a host machine without a phone has been verified; the on-device behavioral component still needs the runbook
- ⏳ = defense is in place; test script exists but hasn't been run yet
- ⚠ = defense is partial / known-leaky; details in the row's notes
- ❌ = no defense (out of scope or kernel/hardware territory)

Two flavors of verification:
- **Host-side**: runnable from any machine with the offensive tools installed (mitmproxy, jadx, apktool, frida-tools, etc. — see SETUP.md). Run `scripts/verify-host-side.sh` to fire the whole host-side suite.
- **Device-side**: requires a real Android phone to observe the runtime defense (FLAG_SECURE blocking screencap, BiometricPrompt firing, accessibility events not carrying password values, etc.). Each runbook walks through it.

A row with ✅ (host) means we've proven the structural defense: the cert digest of a re-signed APK genuinely differs from the pin (so the runtime check would fire), or no embedded credentials are findable in the decompiled source. A full ✅ requires both halves verified.

## Matrix

| # | Threat / tool | Class | Defense | Verified | Test |
|---|---|---|---|---|---|
| 1 | **Frida** dynamic instrumentation | Reverse | `Tamper.fridaInjected()` reads `/proc/self/maps` for frida-agent / frida-gadget / gum-js-loop / linjector. Hard-fail on detection. | ⏳ | [01-frida.md](./01-frida.md) |
| 2 | **Lucky Patcher** repackaging / re-signing | Repackage | `Tamper.signatureMatches()` against pinned SHA-256 of build cert. Hard-fail on mismatch. `Tamper.luckyPatcherInstalled()` enumerates known patcher packages via `<queries>`. | ✅ (host) | [02-repackaging.md](./02-repackaging.md) |
| 3 | **Xposed / LSPosed** hooking | Reverse / hook | `Tamper.hookFrameworkLoaded()` probes canonical bridge classes (`de.robv.android.xposed.XposedBridge`, `org.lsposed.lspd.core.Main`, etc.) via `Class.forName`. Hard-fail. | ⏳ | [03-xposed.md](./03-xposed.md) |
| 4 | **mitmproxy / sslstrip / bettercap** TLS interception | MITM | `network_security_config.xml` with `cleartextTrafficPermitted="false"` and a `<base-config>` denying any custom CA. `INTERNET` permission stripped via `tools:node="remove"` — passgen makes zero network calls regardless. | ⏳ | [04-mitm.md](./04-mitm.md) |
| 5 | **adb screencap** / **scrcpy** / system screen recording | Screen capture | `WindowManager.LayoutParams.FLAG_SECURE` on every activity. `setRecentsScreenshotEnabled(false)` on API 33+. `setHideOverlayWindows(true)` on API 31+. FLAG_SECURE on the IME window too. | ⏳ | [05-screencap.md](./05-screencap.md) |
| 6 | **Tap-jack** via SYSTEM_ALERT_WINDOW overlay | UI redress | `setHideOverlayWindows(true)` blocks overlays from drawing over us. `View.filterTouchesWhenObscured = true` rejects taps through obscured windows. `SecureButton` rejects on `MotionEvent.FLAG_WINDOW_IS_OBSCURED` AND `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`, both DOWN and MOVE. IME `ObscuredTouchGate` does the same. | ⏳ | [06-tapjack.md](./06-tapjack.md) |
| 7 | Malicious **accessibility services** | Sensor / exfil | `A11yProbe` surfaces a banner when a third-party a11y service is enabled. The architectural defense is structural: passgen never renders password values to a `TextView`, so a11y events never carry the value. Delivery is via `commitText` / autofill IPC / clipboard — none of which are reachable through accessibility on the source side. | ⏳ | [07-a11y.md](./07-a11y.md) |
| 8 | **Gboard / Samsung Keyboard clipboard panels** scraping | Exfil | `ClipDescription.EXTRA_IS_SENSITIVE` flag on every clipboard write. Auto-clear after configurable timeout. **Honest known leak**: Samsung Keyboard's clipboard panel retains entries despite the flag. The IME and autofill paths bypass this entirely. | ⚠ | [08-clipboard.md](./08-clipboard.md) |
| 9 | **`adb backup`** data extraction | Backup exfil | `android:allowBackup="false"` and `<data-extraction-rules>` excluding every domain (root, file, database, sharedpref, external). | ⏳ | [09-adb-backup.md](./09-adb-backup.md) |
| 10 | **jadx / apktool / strings** static analysis | Reverse | No hardcoded secrets in source. Vault data is AES-256-GCM-encrypted at rest. The signing-cert digest pin is in source but is itself non-secret. R8 obfuscation in release builds. | ✅ (jadx + dex strings) | [10-static-analysis.md](./10-static-analysis.md) |
| 11 | **Permission abuse** by transitive deps | Supply-chain | All comms / sensor / storage / install / overlay / sip permissions stripped via `tools:node="remove"`. Verified via `aapt2 dump badging`. Transitive libs cannot silently re-add. | ✅ | [11-permissions.md](./11-permissions.md) |
| 12 | **`adb run-as`** / jdwp debugger attach | Reverse | `isDebuggable=false` in *both* debug and release variants. `Debug.isDebuggerConnected()` runtime probe at every entry point. | ✅ | [12-debugger.md](./12-debugger.md) |
| 13 | APK **signature** inspection | Audit | v2 signature scheme. Verifiable by anyone with `apksigner`. Same cert across all builds from this keystore. | ✅ | [13-signature.md](./13-signature.md) |
| 14 | **String extraction** for hardcoded credentials | Reverse | No credentials in source. The vault's master is generated at runtime. The reveal flow uses BiometricPrompt — no shared secret to leak. | ✅ | [14-strings.md](./14-strings.md) |
| 15 | **Memory dump** via `gcore` / `/proc/PID/mem` (root required) | Forensics | `CharArray` hygiene, short secret lifetimes, lock-on-background, BiometricPrompt-gated just-in-time decryption. Locked vault has no plaintext entries findable in process memory. | ⏳ | [15-memdump.md](./15-memdump.md) |

## Suite-level defenses (apply across passgen + aegis + firewall)

These rows depend on multiple suite apps being installed; they're
defenses provided by the *suite mesh* not by any single app.

| # | Threat / tool | Class | Defense | Verified | Test |
|---|---|---|---|---|---|
| 16 | **Repackaged sibling app** — attacker installs a sister suite app with their own keystore alongside the genuine ones | Suite tamper | `SuiteAttestation.verify()` runs at every app's launch. Each app queries every other suite member's package via `<queries>`, reads its `apkContentsSigners`, and refuses to run if any sibling exists with a wrong cert. Symmetric: a tampered sibling drags down every suite app. | ✅ (host) | [16-suite-attestation.md](./16-suite-attestation.md) |
| 17 | **Build-time keystore swap** — developer builds on a different machine without updating the cert pin, ships an APK that hard-fails on launch | Build hygiene | `verifyCertPin` Gradle task at the project root. Runs after every `assemble*` finalizer. Reads pin from `Tamper.kt`, runs `apksigner verify` on every output APK, fails the build on mismatch with the actionable message "update pin to X" or "this APK was signed by an unexpected keystore". | ✅ | [17-cert-pin-gradle.md](./17-cert-pin-gradle.md) |
| 18 | **TOTP code visible to malicious accessibility service** — codes are inherently visible on screen, so a screen-reading a11y service can capture them | Sensor / exfil | Aegis surfaces an A11yProbe banner on the entry list when any third-party accessibility service is enabled. Architectural mitigation: codes are valid only for ~30 seconds, so even an exfiltrated code has a tight expiry window. | ⏳ | [18-totp-a11y.md](./18-totp-a11y.md) |
| 19 | **Aegis vault file extraction** — vault.bin contents on filesystem | Forensics | Identical posture to passgen: AES-256-GCM under Keystore-bound device-credential key, snake-eats-tail master sealed as entry[0], allowBackup=false, dataExtractionRules excludes everything. | ✅ (structural) | [19-aegis-vault.md](./19-aegis-vault.md) |
| 20 | **Lint config drift across modules** — different modules using different lint rule sets, missing real findings | Build hygiene | All four modules reference the same `android/lint.xml`. Rule changes apply suite-wide. False-positive suppressions documented in lint.xml comments. | ✅ | [20-lint-config.md](./20-lint-config.md) |

## What passgen explicitly does NOT defend against

Stated openly so the threat model is honest:

| Out-of-scope | Why |
|---|---|
| Kernel exploits | Userspace can't help. GrapheneOS hardens the kernel; we don't. |
| Bootloader / verified boot bypass | Hardware-rooted trust chain is the OS's job. |
| Baseband / radio firmware backdoors | Opaque vendor blob, unreachable from any app. |
| Hardware attacks (cold-boot RAM, JTAG, side-channels) | Physical access game-over. |
| Compromised system Keystore implementation | We trust Android Keystore + StrongBox. If the TEE is broken, we're broken. |
| User who copies their master entry to plaintext somewhere | Out-of-app data hygiene is the user's responsibility. |
| Targeted social engineering of the user | UX cannot defend against a user who decides to disclose. |
| **An attacker who has your unlocked device in hand and you've already authed** | This is the inherent limitation of any per-device security model. |

## How to use this directory

Each row has a linked Markdown runbook (`NN-tool.md`) describing:

1. The threat / tool (with link to BlackArch package or upstream project)
2. Setup steps (what you need on your laptop, what you need on the phone)
3. The exact commands to run
4. The expected result if our defense is working
5. How to interpret a result that diverges (likely root cause)

`scripts/` holds bash scripts for the tests that can be fully automated
(signature, permissions, backup attempt, strings extraction). Run them
against a built APK on your machine; they need only `aapt2`, `apksigner`,
`adb`, and an attached device for some.

The non-scriptable tests (Frida injection, Lucky Patcher repackage,
overlay tap-jack, etc.) require additional tools and manual verification.
Their runbooks walk through the steps.

## Brand promise

- **Tested against the offensive tools, not just designed to be safe in theory.**
- **Rootless throughout** — every defense uses public Android APIs the way Google intends. No reflection on hidden APIs, no signature spoofing, no accessibility-as-privilege-escalation, no policy violations.
- **In-bounds throughout** — passes Play-Store policy review without exception requests (the things we deliberately don't ship to Play, like the firewall, are because they need policy waivers we're choosing not to seek, not because they violate policy).
- **Verifiable** — every claim in this matrix is backed by a runbook a third party can execute.

If a row in this matrix has ⏳ status and you can't reproduce ✅ on your
device after running the runbook, that's a bug — file it.
