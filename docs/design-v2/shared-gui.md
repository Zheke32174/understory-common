# Design v2 — SHARED GUI / DESIGN-LANGUAGE SYSTEM (common-security)

Owner module: `common-security` (every app already depends on it —
`understory-passgen/settings.gradle.kts` `include(":common-security")`, same in
all seven repos). This doc is DESIGN ONLY. It specifies the exact files to add
to common-security, the exact adoption edit in each app, and the disposition of
every GUI defect the audit found. An implementer builds from this without
re-deriving.

Addresses SUITE.md ship-gap **#9** ("GUI shippable-bar debt, structurally
identical in all seven apps") and the GUI/threading half of **#8**
(main-thread crypto/IO). Honesty-policy hooks (CD-4) are baked into the state
composables so gaps #6-class copy fixes have a home.

---

## 0. WHAT IS BROKEN TODAY (verified this session)

| Defect | Evidence | Fixed by section |
|---|---|---|
| Theme is `MaterialTheme(colorScheme = darkColorScheme())` inline, re-declared per Activity | `passgen/MainActivity.kt:180,233`; `FillSavedEntryActivity.kt:125`; `VaultActivity.kt:40` | §1 UnderstoryTheme |
| ~60+ hardcoded `Color(0xFF…)` per app, and inside shared components | `SuiteStatusFooter.kt:81,97,109,124,145…`; `DiagnosticsScreen.kt:69,73,117,137`; `MainActivity.kt:69` | §1 tokens, §2 refactor |
| Hardcoded `.sp` font sizes, many <12sp | `SuiteStatusFooter.kt` all `9.sp`; `DiagnosticsScreen.kt` `10/11.sp` | §1 typography |
| No light theme, no dynamic color; declared dark-only nowhere honestly | grep: only `darkColorScheme()` exists | §1 |
| No Scaffold / TopAppBar anywhere; each screen is a raw `Column` | `DiagnosticsScreen.kt:65`; `passgen/MainActivity.kt` | §2 SuiteScaffold |
| `strings.xml` = 1–2 entries; all copy hardcoded in Kotlin; `resourceConfigurations=["en"]` | `passgen/strings.xml` (2 strings); `build.gradle.kts:17` | §5 string convention + lint gate |
| Switch/Slider have no merged semantics — TalkBack reads unlabeled control | passgen.md §C ("ToggleRow … TalkBack reads an unlabeled switch"; Slider `:457` no label) | §3 a11y + §2 SettingRow |
| Masked-secret dots no `contentDescription` | passgen.md §C (`VaultActivity.kt:952`) | §3 |
| Main-thread crypto/IO (Argon2id 64 MiB, SAF import parse, QR decode, re-encrypt) → ANR, unrenderable loading states | passgen.md A25/rank 11 (`VaultActivity.kt:740`); SUITE.md #8 (5 apps) | §6 Bg dispatcher + LoadingState |
| No shared empty/loading/error state → every "loading" the audit wanted is unrenderable | passgen.md rank 10 ("empty state for vault list, progress indicators") | §2 state composables |
| Silent tamper hard-fail `finish()` with no message (CD-4 failure honesty) | `passgen/MainActivity.kt` tamper path → `finish(); return` | §2 FatalScreen |
| No dynamic color despite Samsung One UI Material You expectation | — | §1 opt-in |

Design principle: **fix once in common-security, inherit seven times.** No app
keeps its own color/typography/scaffold. An app's only theming freedom is one
accent seed (§1.4) so the seven apps stay visibly a family but each has an
identity.

---

## 1. THE THEME — `UnderstoryTheme`

New files under
`common-security/src/main/java/com/understory/security/ui/`:

```
ui/theme/Color.kt          token palette (dark + light)
ui/theme/Type.kt           Typography
ui/theme/Shape.kt          Shapes
ui/theme/Spacing.kt        spacing scale (CompositionLocal)
ui/theme/Theme.kt          UnderstoryTheme() + UnderstoryAccent enum
```

Package: `com.understory.security.ui.theme`. (New `ui/` subpackage keeps the
security primitives and the design system visibly separate; no existing import
path changes.)

### 1.1 Color tokens — `Color.kt`

Tokens are **named by Material3 color role**, not by hue, so an app never
references a hex again. Every current `Color(0xFF…)` maps to a role below.

Base palette (raw `Color` constants, `internal` — apps never see these):

```kotlin
// Neutrals — the near-black the suite already uses, laddered.
internal val Ink900   = Color(0xFF0A0A0A)  // background (was ad-hoc black)
internal val Ink800   = Color(0xFF111111)  // surface     (SuiteStatusFooter.kt:81/97)
internal val Ink700   = Color(0xFF1A1A1A)  // surfaceVariant / card
internal val Ink600   = Color(0xFF242424)  // outline on dark
internal val Fog500    = Color(0xFF6E6E6E) // onSurfaceVariant dim (footer.kt:124)
internal val Fog300    = Color(0xFF9E9E9E) // secondary text (DiagnosticsScreen.kt:73)
internal val Fog100    = Color(0xFFE0E0E0) // primary text (DiagnosticsScreen.kt:69/137)
// Semantic
internal val Danger    = Color(0xFFEF5350) // error (footer.kt:145, Diag ERROR:117)
internal val Caution   = Color(0xFFFFB74D) // warning (Diag WARN:117)
internal val Success   = Color(0xFF81C784) // success (footer MARK:116)
internal val SuccessDim = Color(0xFF7E9E7E)// verified-peer green (footer.kt:145)
// Light-theme neutrals
internal val Paper50   = Color(0xFFFAFAFA)
internal val Paper100  = Color(0xFFF2F2F2)
internal val Slate900  = Color(0xFF1A1A1A)
internal val Slate600  = Color(0xFF5A5A5A)
```

Two `ColorScheme` builders. Dark is the default and matches today's look
exactly (so no app visually regresses); light is new but complete (CD-4: don't
ship a half-light theme — either full or opt out, §1.5):

```kotlin
internal fun understoryDarkColors(seed: Color) = darkColorScheme(
    primary            = seed,
    onPrimary          = Ink900,
    primaryContainer   = seed.copy(alpha = 0.16f).compositeOver(Ink800),
    onPrimaryContainer = Fog100,
    background         = Ink900,
    onBackground       = Fog100,
    surface            = Ink800,
    onSurface          = Fog100,
    surfaceVariant     = Ink700,
    onSurfaceVariant   = Fog300,
    outline            = Ink600,
    outlineVariant     = Ink700,
    error              = Danger,
    onError            = Ink900,
    // extras used via semantic accessors §1.3
)
internal fun understoryLightColors(seed: Color) = lightColorScheme(
    primary = seed, onPrimary = Paper50,
    background = Paper50, onBackground = Slate900,
    surface = Paper100, onSurface = Slate900,
    surfaceVariant = Paper100, onSurfaceVariant = Slate600,
    outline = Color(0xFFCFCFCF), error = Danger, onError = Paper50,
)
```

### 1.2 Semantic extras (warning/success/dim) — not in Material's role set

Material3 `ColorScheme` has no `warning`/`success`. The audit needs them
(WARN=Caution, verified-peer=SuccessDim). Add an immutable holder exposed via a
`CompositionLocal` so `MaterialTheme.colorScheme` stays standard:

```kotlin
@Immutable data class SuiteSemanticColors(
    val warning: Color, val onWarning: Color,
    val success: Color, val onSuccess: Color,
    val dim: Color,            // Fog500 — was footer's 0xFF6E6E6E "suite"/"no peers"
)
val LocalSuiteColors = staticCompositionLocalOf { /* dark defaults */ }
// usage: UnderstoryTheme.semantic.warning  (accessor object §1.3)
```

### 1.3 Accessor object — how an app reads tokens

```kotlin
object UnderstoryTheme {
    val colors: ColorScheme  @Composable get() = MaterialTheme.colorScheme
    val semantic: SuiteSemanticColors @Composable get() = LocalSuiteColors.current
    val spacing: Spacing     @Composable get() = LocalSpacing.current
    val type: Typography     @Composable get() = MaterialTheme.typography
    val shapes: Shapes       @Composable get() = MaterialTheme.shapes
}
```

Rule for apps: **any color you would have written `Color(0xFF…)` for now comes
from `MaterialTheme.colorScheme.X` or `UnderstoryTheme.semantic.X`.** Migration
table for the two shared components proves coverage (§2.1).

### 1.4 Typography — `Type.kt`

M3 type scale with a **minimum body size of 14sp** (kills the sub-12sp finding;
`bodySmall` = 12sp is the floor, used only for captions, never body). Uses the
platform default font family (no bundled font — keeps APK small, matches the
utilitarian posture):

```kotlin
val UnderstoryType = Typography(
    displaySmall  = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = Medium),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = Medium), // was Diag "diagnostics" 22.sp
    titleLarge    = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = Medium), // TopAppBar title
    titleMedium   = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = Medium),
    bodyLarge     = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium    = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),   // BODY FLOOR
    bodySmall     = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),   // captions only
    labelSmall    = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),   // footer/monospace status ONLY (§2.1 exception)
    labelMedium   = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)
```

The footer's 9sp status text (`SuiteStatusFooter.kt` all `9.sp`) becomes
`labelSmall` (11sp) — a deliberate, documented exception that the status
footer is the ONE surface allowed below 12sp; §3 gives it a TalkBack-off
posture so the size doesn't harm a11y.

### 1.5 Shape & Spacing

`Shape.kt`:
```kotlin
val UnderstoryShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),  // Diag event rows (was 4.dp)
    small      = RoundedCornerShape(6.dp),  // footer container (was 6.dp)
    medium     = RoundedCornerShape(10.dp), // cards
    large      = RoundedCornerShape(16.dp),
)
```
`Spacing.kt` — a 4dp-grid scale via CompositionLocal (replaces scattered
`padding(6/8/10/16/20.dp)`):
```kotlin
@Immutable data class Spacing(
    val xs: Dp = 4.dp, val sm: Dp = 8.dp, val md: Dp = 12.dp,
    val lg: Dp = 16.dp, val xl: Dp = 24.dp, val xxl: Dp = 32.dp,
)
val LocalSpacing = staticCompositionLocalOf { Spacing() }
```

### 1.6 The wrapper — `Theme.kt`

```kotlin
enum class UnderstoryAccent(val seed: Color) {           // one per app = family-but-distinct
    PASSGEN(Color(0xFF7E9E7E)), AEGIS(Color(0xFF8AA3C9)),
    VAULTFOLDER(Color(0xFFB08AC9)), BACKUPS(Color(0xFF8AC9B0)),
    BROWSER(Color(0xFFC9B08A)), FIREWALL(Color(0xFFC98A8A)),
    ANTIVIRUS(Color(0xFF8AC9C9)),
}

@Composable
fun UnderstoryTheme(
    accent: UnderstoryAccent,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,        // OPT-IN, default off (§1.7)
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme  -> understoryDarkColors(accent.seed)
        else       -> understoryLightColors(accent.seed)
    }
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    CompositionLocalProvider(
        LocalSuiteColors provides semantic,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography  = UnderstoryType,
            shapes      = UnderstoryShapes,
            content     = content,
        )
    }
}
```

### 1.7 Dynamic color = opt-in, default OFF (rationale)

`dynamicColor` defaults `false`: the suite's whole identity is the dim-neutral
security look, and dynamic color would let the wallpaper repaint a
"security tool" in arbitrary hues (an honesty smell — a green "safe" chip could
render pink). Apps may pass `dynamicColor = true` if they later add a
user setting; the plumbing is present, the default is conservative.

### 1.8 How an app switches from its hardcoded palette to tokens

Per app, mechanical, no behavior change:

1. Delete every `MaterialTheme(colorScheme = darkColorScheme())` call
   (passgen: `MainActivity.kt:180,233`, `FillSavedEntryActivity.kt:125`,
   `VaultActivity.kt:40`) and replace with
   `UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) { … }`.
2. Delete the app's `import …darkColorScheme` and every
   `import …graphics.Color` that only fed a hardcoded hex.
3. Replace `Color(0xFF…)` literals per the §1.1 role mapping. A one-time
   ripgrep `Color\(0xFF` in each app's `src/main` yields the worklist; each hit
   maps to a `MaterialTheme.colorScheme.*` or `UnderstoryTheme.semantic.*`
   role. **The lint gate in §5.3 (`HardcodedColor`) makes this permanent** —
   after migration a new hex literal fails the build.
4. Replace `fontSize = N.sp` with `style = MaterialTheme.typography.X`.
5. Replace `Surface(color = MaterialTheme.colorScheme.background)` boilerplate
   with `SuiteScaffold` (§2).

---

## 2. SHARED COMPONENTS (add / extend in common-security)

New file `ui/components/SuiteComponents.kt` (+ `SuiteScaffold.kt`,
`SuiteStates.kt`, `SuiteDialogs.kt`). All consume tokens from §1; none contains
a hex literal or a bare `.sp`. Existing `SecureButton`/`secureClickable`
(`SecureButton.kt`) and `SuiteStatusFooter` are **reused** (footer refactored
in §2.1, not replaced).

### 2.1 Refactor the two existing shared components onto tokens (proof-of-coverage)

`SuiteStatusFooter.kt` — replace hexes/sizes; behavior identical:

| Current literal | Replacement |
|---|---|
| `Color(0xFF111111)` container (`:81,:97`) | `MaterialTheme.colorScheme.surface` |
| `Color(0xFF6E6E6E)` "suite"/"no peers" (`:124,:137,:151`) | `UnderstoryTheme.semantic.dim` |
| `Color(0xFF1F3A1F)`/`0xFF81C784` MARK pill (`:109,:116`) | `semantic.success.copy(alpha=.18f)` / `semantic.success` |
| `Color(0xFF7E9E7E)` verified peer (`:145`) | `semantic.success` |
| `Color(0xFFEF5350)` bad cert (`:145`) | `MaterialTheme.colorScheme.error` |
| `9.sp` (all) | `MaterialTheme.typography.labelSmall` |
| `RoundedCornerShape(6/3.dp)` | `MaterialTheme.shapes.small` / `extraSmall` |

`DiagnosticsScreen.kt` — same treatment: `0xFFE0E0E0`→`onSurface`,
`0xFF9E9E9E`→`onSurfaceVariant`, `0xFF707070`→`semantic.dim`, INFO/WARN/ERROR
accents (`:117`)→`onSurfaceVariant`/`semantic.warning`/`error`; wrap body in
`SuiteScaffold(title="Diagnostics")` and make the Copy/Clear/Back row use
`SecureOutlinedButton` + `EmptyState` for the empty branch (`:94`).

### 2.2 `SuiteScaffold` — top bar + status footer, one per screen

Replaces every raw `Column`/`Surface` root. Wires the top bar and the reused
`SuiteStatusFooter` so every app gets identical chrome.

```kotlin
@Composable
fun SuiteScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,          // shows back arrow when non-null
    actions: @Composable RowScope.() -> Unit = {},
    showSuiteFooter: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                             contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (showSuiteFooter) SuiteStatusFooter(
                Modifier.padding(horizontal = UnderstoryTheme.spacing.md,
                                 vertical = UnderstoryTheme.spacing.sm))
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad -> content(pad) }
}
```
- Uses M3 `Scaffold`/`TopAppBar` (`material3`; add `material-icons-extended` to
  common-security deps — already in app gradles, e.g. passgen `:111`).
- Fixes SUITE #9 "no Scaffold/TopAppBar". Every app's main screen becomes
  `SuiteScaffold(title = stringResource(R.string.app_name)) { pad -> … }`.

### 2.3 State composables — `SuiteStates.kt` (fixes "unrenderable loading states")

Because main-thread work (§6) is being moved off-thread, screens finally *can*
show these. Three composables + one sealed `UiState<T>` helper:

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data class Empty(val message: String) : UiState<Nothing>
    data class Error(val message: String, val onRetry: (() -> Unit)? = null) : UiState<Nothing>
}

@Composable fun LoadingState(label: String = stringResource(R.string.state_loading), modifier: Modifier = Modifier)
    // centered CircularProgressIndicator + label; semantics { contentDescription = label; liveRegion = Polite }

@Composable fun EmptyState(title: String, body: String? = null, icon: ImageVector? = null,
                           action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier)
    // icon + title(titleMedium) + body(bodyMedium onSurfaceVariant) + optional CTA slot

@Composable fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier)
    // error-tinted card; message + optional Retry SecureOutlinedButton; NEVER swallows — this is the
    // CD-4 "failure honesty" surface. Replaces silent dead-ends.

@Composable fun <T> UiStateHost(state: UiState<T>, modifier: Modifier = Modifier,
                                ready: @Composable (T) -> Unit)
    // when-dispatch over the four states so callers write one line
```

`EmptyState` directly supplies passgen rank 10 "empty state for vault list".
`LoadingState` supplies "progress indicators" (A25/rank 11). `ErrorState` is
where swallowed taps / silent hard-fails (CD-4c) surface a truthful message.

### 2.4 `FatalScreen` — replaces silent `finish()` on tamper/attestation fail

Today the tamper path does `finish(); return` with no user message
(`passgen/MainActivity.kt` tamper block). CD-4c requires honesty. Add:

```kotlin
@Composable fun FatalScreen(title: String, reason: String, details: String? = null)
```
Full-screen error surface (error container, `headlineSmall` title, `bodyMedium`
reason, expandable `details` in `labelSmall` monospace). Apps render this
instead of blind `finish()`. This also gives the app-specific `renderDiagnostic`
(`MainActivity.kt:198+`) a shared home so its hex/`sp` literals die too.

### 2.5 `SuiteCard` / `SuiteSection` — the card + section-header style

```kotlin
@Composable fun SuiteCard(modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
    // Surface(color=surfaceVariant, shape=shapes.medium, tonalElevation),
    // padding spacing.lg; if onClick != null uses Modifier.secureClickable (reuse SecureButton.kt),
    // minHeight 48.dp, role=Button semantics.
@Composable fun SuiteSectionHeader(text: String)  // titleMedium, onSurfaceVariant, padding (lg, md, lg, sm)
```
Replaces the ad-hoc `Box(background(accent.copy(alpha=.06f), RoundedCornerShape(4.dp)))`
pattern (`DiagnosticsScreen.kt:119`) and every app's inline card.

### 2.6 `SuiteListRow` + `SettingRow` — consistent rows (with a11y baked in)

```kotlin
@Composable fun SuiteListRow(
    headline: String, supporting: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
)  // M3 ListItem, minHeight 56.dp, whole-row secureClickable, merged semantics

@Composable fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean)->Unit,
                          supporting: String? = null, enabled: Boolean = true)
    // Row { Column(label+supporting); Switch }.  CRITICAL a11y fix:
    // Modifier.toggleable(value=checked, role=Role.Switch, onValueChange=onCheckedChange)
    //         .semantics(mergeDescendants = true) {}
    // -> TalkBack reads "<label>, switch, on". Fixes passgen ToggleRow finding.

@Composable fun SliderRow(label: String, value: Float, onValueChange: (Float)->Unit,
                          valueRange: ClosedFloatingPointRange<Float>, valueText: String)
    // Slider with Modifier.semantics { contentDescription = "$label: $valueText"
    //   stateDescription = valueText; progressBarRangeInfo = … }
    // Fixes passgen Slider ":457 no semantic label".
```

`SwitchRow`/`SliderRow` are the **only** sanctioned way to place a Switch or
Slider in the suite; a lint note (§5.3) flags bare `Switch(`/`Slider(` outside
common-security. This closes the two named TalkBack findings suite-wide in one
component each.

### 2.7 Confirm-destructive dialog — `ConfirmDestructiveDialog`

```kotlin
@Composable fun ConfirmDestructiveDialog(
    visible: Boolean,
    title: String, body: String,
    confirmLabel: String,            // e.g. "Delete vault"
    onConfirm: () -> Unit, onDismiss: () -> Unit,
    requireHold: Boolean = false,    // for irreversible ops: press-and-hold 800ms to confirm
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null,
                      tint = MaterialTheme.colorScheme.error) },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text  = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            // SecureButton (reuse SecureButton.kt) tinted error; tap-jacking-resistant
            SecureButton(onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text(confirmLabel) }
        },
        dismissButton = { SecureOutlinedButton(onClick = onDismiss) {
            Text(stringResource(R.string.cancel)) } },
    )
}
```
- Uses the existing tap-jacking-hardened `SecureButton`/`SecureOutlinedButton`
  so destructive confirms can't be tap-jacked through an overlay.
- The single home for every "delete vault / wipe / reset" confirm across the
  suite — supports ship-gap #3's guarded reset path with a consistent surface.

### 2.8 Component inventory (what each app adopts)

| Component | Replaces in apps | New file |
|---|---|---|
| `UnderstoryTheme` | inline `darkColorScheme()` ×N | `ui/theme/Theme.kt` |
| `SuiteScaffold` | raw `Surface`/`Column` roots | `ui/components/SuiteScaffold.kt` |
| `LoadingState`/`EmptyState`/`ErrorState`/`UiStateHost` | absent (new capability) | `ui/components/SuiteStates.kt` |
| `FatalScreen` | silent `finish()`, per-app `renderDiagnostic` | `ui/components/SuiteStates.kt` |
| `SuiteCard`/`SuiteSectionHeader` | ad-hoc `Box`+`background` cards | `ui/components/SuiteComponents.kt` |
| `SuiteListRow`/`SwitchRow`/`SliderRow` | inline `Row{Text;Switch}`, `Slider` | `ui/components/SuiteComponents.kt` |
| `ConfirmDestructiveDialog` | inline/absent confirms | `ui/components/SuiteDialogs.kt` |
| `SecureButton`/`secureClickable` (REUSE) | — | existing `SecureButton.kt` |
| `SuiteStatusFooter` (REFACTOR to tokens) | — | existing `SuiteStatusFooter.kt` |

---

## 3. A11Y BASELINE (normative — every app must meet)

Encoded partly in the components above so apps get it for free; the rest is a
checklist the review gate enforces.

**A-1 · Touch targets ≥ 48dp.** All interactive shared components set
`Modifier.defaultMinSize(minHeight = 48.dp)` (rows 56dp). Rule: no `IconButton`
/ tap area smaller than 48dp. `SuiteListRow`, `SwitchRow`, `SuiteCard(onClick)`
already comply.

**A-2 · contentDescription conventions.**
- Icons that are purely decorative → `contentDescription = null` (explicit, not
  omitted).
- Icons that ARE the control (back arrow, action icons) → a
  `stringResource(R.string.cd_*)` label. Convention: string ids prefixed
  `cd_` (`cd_back`, `cd_copy`, `cd_delete`, `cd_reveal`, `cd_hide`).
- **Masked-secret dots** (passgen `VaultActivity.kt:952`) get
  `contentDescription = stringResource(R.string.cd_password_hidden)` = "Password
  hidden" — never the value. A `RevealToggle` helper composable
  (in `SuiteComponents.kt`) enforces: hidden state announces "hidden", shown
  state announces the label only, never speaks the secret characters.

**A-3 · Switch / Slider semantics.** Mandatory via `SwitchRow`/`SliderRow`
(§2.6): `Role.Switch` + `toggleable` merged semantics; Slider gets
`stateDescription` + `progressBarRangeInfo`. Bare `Switch(`/`Slider(` outside
common-security is a lint finding (§5.3).

**A-4 · Contrast.** Token pairs are chosen ≥ 4.5:1 for body, ≥ 3:1 for
large/labels: Fog100-on-Ink900 ≈ 17:1, Fog300-on-Ink900 ≈ 7:1,
Fog500-on-Ink800 ≈ 3.6:1 (labelSmall/large only — the footer, which is
excused from TalkBack per A-6). Danger/Caution/Success all clear 4.5:1 on
Ink900. Any new token must be added with its measured ratio in a `// contrast:`
comment; the design-review gate checks it.

**A-5 · TalkBack pass.** Every screen must be operable end-to-end with TalkBack:
logical focus order (top bar → content → footer), no focus traps, every action
reachable. `mergeDescendants` on rows so a row reads as one node, not three.
`LoadingState` sets `liveRegion = Polite` so progress is announced.

**A-6 · The one exception — SuiteStatusFooter.** It is `labelSmall` (11sp),
dim, purely informational (a runtime smoke-test surface, not a control). It
sets `Modifier.clearAndSetSemantics {}` in production so TalkBack skips it
rather than reading four dim status lines on every screen open. (Eng-build
triple-tap keeps its clickable but still no a11y text — it's a debug affordance.)

**A-7 · Text scaling.** All sizes in `sp` (they are, via Typography), no fixed
`dp` text, no `maxLines=1` on body copy without `ellipsis` + it must still be
reachable; layouts use `wrapContentHeight`, never fixed heights on text
containers, so 200% font scale doesn't clip.

---

## 4. STRINGS CONVENTION + LINT GATE

**S-1 · Every user-facing string lives in `strings.xml`.** No user-visible
literal in Kotlin. Today `passgen/strings.xml` has 2 entries and all copy is
hardcoded — that inverts. Shared strings used by common-security components
(`cd_back`, `cancel`, `state_loading`, `state_empty_generic`,
`state_error_generic`, `retry`, `cd_password_hidden`) ship in
`common-security/src/main/res/values/strings.xml` so every app inherits them;
app-specific copy lives in the app's `strings.xml`.

**S-2 · Naming.** `snake_case`, grouped by prefix: `cd_*` (contentDescription),
`state_*` (empty/loading/error), `action_*` (buttons), `title_*`, `msg_*`,
`err_*`. Format args numbered (`%1$s`) for future translation.

**S-3 · Drop `resourceConfigurations = ["en"]`** from each app
`build.gradle.kts` (passgen `:17`). It hard-locks the app to English and blocks
any future localization; removing it costs nothing today (only `en` exists) and
unblocks translation later. (This is the honest form of the audit's
"declare/enable proper resources" note.)

**S-4 · Lint gate — make it permanent.** In the shared `lint.xml`
(`understory-common/lint.xml`, already the referenced config), flip
`HardcodedText` from informational to **error** (the comment already lists it as
a "real finding"; enforce it). This fails any build with a user-facing string
literal in a layout/Compose text. Compose `HardcodedText` coverage is partial,
so §5.3 adds the reinforcing custom detector.

---

## 5. THREADING CONVENTION + GATES

### 5.1 The problem

SUITE #8: main-thread Argon2id (64 MiB), vault re-encrypt, QR decode, SAF
import parse (passgen `VaultActivity.kt:740` reads+parses on main thread),
file encrypt/export — all ANR-class, and they make every loading state
unrenderable. common-security has **no** dispatcher/coroutine util today
(grep confirms zero `Dispatchers`/`withContext` in the module).

### 5.2 The shared util — `ui/Bg.kt` (new)

```kotlin
package com.understory.security.ui
object Bg {
    /** Single place the whole suite names its background dispatcher.
     *  Crypto/IO must run here, never on Dispatchers.Main. */
    val io: CoroutineDispatcher = Dispatchers.IO
    val cpu: CoroutineDispatcher = Dispatchers.Default   // Argon2id / QR decode
}

/** Run [block] off-main and reflect it as UiState. Callers write one line and
 *  get Loading→Ready/Error for free — pairs with UiStateHost (§2.3). */
@Composable
fun <T> produceUiState(vararg keys: Any?, block: suspend () -> T): State<UiState<T>> =
    produceState<UiState<T>>(UiState.Loading, *keys) {
        value = runCatching { withContext(Bg.cpu) { block() } }
            .fold({ UiState.Ready(it) }, { UiState.Error(it.messageForUser()) })
    }
```

**Convention (normative):**
- **T-1** Any crypto (`Crypto.*`, Argon2id, KEK wrap/unwrap, GCM), any SAF/file
  read/write/parse, any QR encode/decode runs inside `withContext(Bg.io)` or
  `Bg.cpu` — never on the composition/main thread.
- **T-2** UI calls these via `viewModelScope`/`rememberCoroutineScope().launch`
  or `produceUiState`, renders `LoadingState` meanwhile (now visible because the
  main thread is free), and shows `ErrorState`/`FatalScreen` on failure.
- **T-3** Input size caps happen BEFORE `readText()` (passgen rank 11) — the
  import path checks length, then parses on `Bg.io`.
- Concrete first conversions: passgen import (`VaultActivity.kt:691-747`)
  → `Bg.io` + `LoadingState`; the four vault engines' unlock/re-encrypt
  (`Vault.kt`, `AegisVault.kt`, `VaultFolder.kt`, `BackupsVault.kt`) → `Bg.cpu`;
  aegis QR decode; backups envelope encode/decode.

### 5.3 Lint / review gates (three, all in the shared config)

1. **`HardcodedText` = error** (§S-4) — strings gate.
2. **Custom lint detector `UnderstoryHardcodedColor`** (new, ships in a tiny
   `common-lint` check module or as a `lint.xml` regex-backed note): flags
   `Color(0x…)` and `.sp`/`.dp` *font* literals anywhere outside
   `com.understory.security.ui.theme`. This is what makes the token migration
   (§1.8) irreversible — after cutover, a reintroduced hex fails CI.
3. **`BlockingCallsInComposition` / `RememberInComposition`-style review rule**:
   ship a `docs/design-v2/gui-review-checklist.md` (design-review gate, since
   lint can't see "this crypto call is on the main thread") requiring: no
   `Crypto.*`/file/QR call in a `@Composable` body or `onClick` without a
   `Bg.*`/coroutine hop; bare `Switch(`/`Slider(` outside common-security;
   sub-12sp text outside the footer; missing `contentDescription` on control
   icons. The existing CI (`.github/workflows`) runs `lint` already
   (`abortOnError = true` in `common-security/build.gradle.kts`), so gates 1–2
   are enforced the moment the config lands.

---

## 6. GRADLE / WIRING CHANGES (common-security)

`common-security/build.gradle.kts` — add to `dependencies`:
```kotlin
api("androidx.compose.material3:material3")                 // already implementation; promote to api
api("androidx.compose.material:material-icons-extended:…")  // for Scaffold icons (apps already have it)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1") // Bg.io/cpu, produceUiState
```
(material3 already present as `implementation`; promoting the design-system
deps to `api` means apps inherit the components without re-declaring. Coroutines
is new to the module.) No change to `minSdk 33` / `compileSdk 35` /
`namespace`. Dynamic color APIs (`dynamicDarkColorScheme`) are SDK-31+, guarded
by the `Build.VERSION.SDK_INT >= 31` check in §1.6 (minSdk is 33, so always
available — the check is documentation).

No new module is strictly required; if the custom color lint (gate §5.3 #2) is
implemented as a real detector rather than config, add a `common-lint`
`com.android.lint` module and `lintChecks(project(":common-lint"))` in each app.

---

## 7. ADOPTION ORDER (per app, identical recipe)

For each of the seven apps (passgen first as the reference, it already fixed the
authority collision so it's cleanest):

1. Wrap each Activity's `setContent` in
   `UnderstoryTheme(accent = UnderstoryAccent.<APP>) { … }`; delete inline
   `darkColorScheme()`.
2. Replace the root `Surface`/`Column` with `SuiteScaffold(title = …)`.
3. Run `rg 'Color\(0x'` and `rg '\.sp'` in `src/main`; map each to a token /
   typography style per §1.1 / §1.4.
4. Replace inline `Row{Text;Switch}` → `SwitchRow`; `Slider` → `SliderRow`;
   ad-hoc cards → `SuiteCard`; list items → `SuiteListRow`.
5. Move every user-facing literal to `strings.xml` (§4); delete
   `resourceConfigurations = ["en"]`.
6. Wrap crypto/IO/QR/SAF in `Bg.*` (§5.2); render `LoadingState`; replace silent
   `finish()` with `FatalScreen`; add `EmptyState` to empty lists.
7. Add `cd_*` strings; verify TalkBack pass (§3) and 48dp targets.
8. Build with the tightened `lint.xml`; fix findings until green.

After app #1, apps #2–7 are mechanical repeats — the whole point of putting the
system in common-security.

---

## 8. DISPOSITION OF EVERY AUDITED GUI ITEM

| Audit item | Disposition |
|---|---|
| 7× inline `darkColorScheme()` | **REDESIGN** → single `UnderstoryTheme` (§1) |
| ~60 hex colors/app, hexes in shared components | **FIX** → tokens + refactor footer/Diag (§1,§2.1); lint-locked (§5.3) |
| sub-12sp / hardcoded `.sp` | **FIX** → Typography, 14sp body floor; footer excepted+TalkBack-off (§1.4,§3 A-6) |
| No light theme | **REDESIGN** → full light scheme, `isSystemInDarkTheme()` (§1.1,§1.6) |
| No dynamic color | **FIX** → opt-in, default off, plumbed (§1.7) |
| No Scaffold/TopAppBar | **FIX** → `SuiteScaffold` (§2.2) |
| No empty/loading/error states | **FIX** → `SuiteStates.kt` (§2.3) |
| Silent tamper `finish()` | **REDESIGN** → `FatalScreen`, CD-4 honesty (§2.4) |
| `strings.xml` = 1–2 entries; hardcoded copy | **FIX** → strings convention + `HardcodedText`=error (§4) |
| `resourceConfigurations=["en"]` lock | **DROP** the lock (§S-3) |
| Switch no merged semantics (passgen ToggleRow) | **FIX** → `SwitchRow` `Role.Switch` (§2.6,§3) |
| Slider no semantic label | **FIX** → `SliderRow` semantics (§2.6,§3) |
| Masked dots no contentDescription | **FIX** → `RevealToggle` + `cd_password_hidden` (§3 A-2) |
| Main-thread crypto/IO (5 apps) | **FIX** → `Bg.io/cpu` + `produceUiState` (§5) |
| Loading states unrenderable | **FIX** (consequence of §5 freeing main thread) |
| No 48dp targets | **FIX** → `defaultMinSize` in components (§3 A-1) |
| Portrait-lock / non-resizable | **KEEP** — deliberate security posture (lint.xml already excuses it); not a GUI defect |
| IME `importantForAccessibility=NO` | **KEEP as documented limitation** — security-vs-a11y tradeoff; note it in-app, out of scope for this system |

Nothing here is DROP-because-impossible: every item is FIX/REDESIGN in
common-security or a one-line app edit. The only DROP is the English resource
lock, which is pure gain.
