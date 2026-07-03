# SHARED-GUI review checklist (design-review gate)

Enforces the parts of `shared-gui.md` that Android Lint cannot see. A change to
any app's UI, or to a shared `com.understory.security.ui.*` component, must pass
every item below before merge. Lint covers `HardcodedText` (§4) and — once the
`common-lint` detector lands — `UnderstoryHardcodedColor` (§5.3 #2); everything
here is the human-review complement.

## Threading (§5)

- [ ] No `Crypto.*`, Argon2id, KEK wrap/unwrap, GCM, SAF/file read-write-parse,
      or QR encode/decode runs in a `@Composable` body or an `onClick` without a
      `Bg.io` / `Bg.cpu` / coroutine hop. Use `produceUiState` or a
      `viewModelScope` / `rememberCoroutineScope().launch`.
- [ ] Input size caps are checked BEFORE `readText()` / parse (T-3).
- [ ] Long ops render `LoadingState` while running and `ErrorState` /
      `FatalScreen` on failure — never a frozen main thread.

## Tokens (§1)

- [ ] No `Color(0x…)` literal outside `com.understory.security.ui.theme`.
- [ ] No bare font-size `.sp` literal; text uses `MaterialTheme.typography.*`.
- [ ] Body text is ≥ 14sp (`bodyMedium` floor). The only sub-12sp surface is
      `SuiteStatusFooter` (`labelSmall`, 11sp), which is TalkBack-excused (A-6).
- [ ] New tokens carry a measured `// contrast:` ratio comment (A-4).

## Components (§2)

- [ ] Screen root is `SuiteScaffold`, not a raw `Surface` / `Column`.
- [ ] Cards use `SuiteCard`; section headers use `SuiteSectionHeader`; list rows
      use `SuiteListRow`.
- [ ] Silent `finish()` on tamper/attestation is replaced by `FatalScreen`.
- [ ] Destructive confirms go through `ConfirmDestructiveDialog`
      (`requireHold = true` for irreversible ops).

## Accessibility (§3)

- [ ] Every Switch is a `SwitchRow`; every Slider is a `SliderRow`. No bare
      `Switch(` / `Slider(` outside `common-security`.
- [ ] Control icons (back arrow, actions) carry a `cd_*` `contentDescription`;
      decorative icons are explicitly `contentDescription = null`.
- [ ] Masked secrets never expose their value to TalkBack — use `RevealToggle`
      / `cd_password_hidden`.
- [ ] Interactive targets are ≥ 48dp (rows ≥ 56dp).
- [ ] Screen is operable end-to-end with TalkBack: logical focus order
      (top bar → content → footer), no focus traps.
- [ ] Layout survives 200% font scale (no fixed-height text containers, no
      `maxLines = 1` on body without ellipsis).

## Strings (§4)

- [ ] No user-facing literal in Kotlin; all copy lives in `strings.xml`.
- [ ] Shared component strings live in
      `common-security/src/main/res/values/strings.xml`; app copy in the app's.
- [ ] `resourceConfigurations = ["en"]` is NOT present in any app
      `build.gradle.kts` (S-3).
