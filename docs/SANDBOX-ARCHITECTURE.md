# sandbox — architecture / option-space

Phase 2 of the suite. Goal as captured in `SUITE_DESIGN.md`: a vfone-class
container, root-capable guest running inside an unrooted host, that lets
the user run apps they don't fully trust without those apps having access
to the host's vault contents, network, contacts, files.

**This document is a map of the territory, not a final design.** Three
candidate architectures are described with honest trade-offs. The decision
is left for the suite owner to make — the constraints here are real and
the bets are months-of-work bets, not afternoon experiments.

## Constraints

These shape what's even possible, before any UX considerations:

### Host kernel (this development environment as observed)
- No KVM, no virtualization extensions
- `CONFIG_ANDROID_BINDER_IPC` not set in `/proc/config.gz`
- No module loading (`CONFIG_MODULES` not set; kernel is monolithic)
- Capabilities: full root, `cap_sys_admin`, `cap_sys_chroot`, etc., but
  these don't help us add features the kernel was built without.

See `ENVIRONMENT_NOTES.md` (repo root) for the full picture. The same
constraints will apply on most Android user devices — emulator-class
features (KVM, binder) are not exposed.

### Android-as-deployment-target
- The suite ships to consumer Android phones (Android 13+). We do not
  control the host kernel; it's whatever the OEM shipped.
- We do NOT require root. The suite's whole posture is "stays correct
  on a stock unrooted phone."
- Hidden / non-SDK API restrictions tightened significantly in Android
  13 → 14 → 15. Plugin-framework approaches (VirtualApp / DroidPlugin /
  VirtualXposed) leaned heavily on hidden APIs that are now blocked or
  signature-changed at random release boundaries.

### Suite goals (from SUITE_DESIGN.md)
- "User installs an app they want to use but don't fully trust."
- "Contained app cannot reach the host's vault data, contacts, files,
  unfiltered network."
- "Suite quality bar: defaults are correct; user doesn't have to
  configure their way to safe."

### Non-goals (deliberate)
- We are NOT building a way for users to bypass app integrity checks
  (the original Magisk/VirtualXposed market). That's an offensive use.
- We are NOT building a way to clone-and-run pirated apps. That's
  what attracted regulatory + Play Store attention to the plugin-
  framework space.

## The option space

### Option A — Application-level virtualization (VirtualApp-class fork)

The original CLAUDE.md / SUITE_DESIGN.md called this "vfone-class."
Lineage: DroidPlugin (2014) → VirtualApp (2016) → VirtualXposed (2017)
→ VirtualApp2022 (community fork, Android 11 limit) → various 2024
forks of varying quality.

**Mechanism**: a host app loads guest APKs into its own process /
process group, intercepts the Android framework via reflection, hooks
Activity lifecycle, ContentProvider, PackageManager, Binder calls,
storage paths. The guest "thinks" it's a normal app but every framework
call is mediated.

**Pros**:
- Multi-instance: can clone an app and run two copies.
- Doesn't need root.
- Doesn't need any kernel features the user's phone might lack.
- Strong user-facing control: the host app sees every framework call.

**Cons**:
- **Architectural fragility on Android 13+**. VirtualXposed officially
  supported 5.0–10.0; community forks reach 11. Android 13/14/15
  tightened non-SDK API restrictions in ways that break large parts of
  the framework-hooking technique. RestrictionBypass / FreeReflection
  exist but are themselves fragile and increasingly cat-and-mouse.
- **Maintenance burden**: every Android release ships hidden-API
  signature changes that break some path. e.g. Android 14 QPR2 changed
  `IPermissionManager` method signatures (per AppManager issue #1308),
  which broke a class of frameworks.
- **Reputation / regulatory exposure**: the plugin-framework space has
  been heavily abused by malware (Palo Alto Unit 42 documents this as
  a major Android adware vector). Play Store policy actively rejects
  apps that load arbitrary external code. Even if our use is benign,
  the architectural shape is the same as the abused one.
- **Time cost**: a from-scratch fork-and-modernize would be 3-6 months
  for one developer, with ongoing maintenance > the build cost.
- **Security guarantees are user-space**. The host app has access to
  the guest's framework calls, and the guest can in theory escape via
  any unhooked path. Defense in depth requires hooking *everything*.

**Existing forks**:
- [android-hacker/VirtualXposed](https://github.com/android-hacker/VirtualXposed) — original, Android 5–10
- [exbug/VirtualXposed](https://github.com/exbug/VirtualXposed) — fork
- [elfland/Android-VirtualApp](https://github.com/elfland/Android-VirtualApp) — open MultiAccount implementation
- VirtualApp2022 — community fork, Android 11 ceiling
- Twoyi — adjacent but heavier (whole ROM virtualization, not app-level)

**Verdict for our context**: Conceptually the most feature-rich, but
real exposure to Android-version churn. Would commit us to chasing
Android internals indefinitely. The malware-adjacent reputation matters
for a sovereignty-flavored security suite that wants to be trusted by
its users.

### Option B — Work-profile-based isolation (Shelter-class)

[Shelter](https://github.com/PeterCxy/Shelter) (also: Insular, Island,
TrackerControl) uses Android's native `DevicePolicyManager` Work Profile
API — the same mechanism corporate MDMs use to separate "work" data
from "personal" data on a BYOD phone.

**Mechanism**: the host app provisions a Work Profile (a separate
Android user with its own UID space, file system, cookie jar, contact
store, network stack, package list). Apps installed into the profile
are isolated from the host's data at the OS level. Shelter adds a UX:
"clone this app into the work profile" and "freeze this app so it
can't run when not in use."

**Pros**:
- **Uses official, stable Android API**. The work-profile API is what
  every enterprise MDM in the world uses; Google has strong incentive
  to keep it working across Android versions. Future-proof in a way
  no plugin-framework can be.
- **Real OS-level isolation**. Work profile is a separate Android user
  — apps in it cannot read the host's data via any Android API path.
  Network / files / contacts / accounts all separate. The same isolation
  property OEM "Secure Folder" features rely on.
- **No root, no kernel features required**. Works on any Android 7+ phone.
- **Existing FOSS reference implementation** (Shelter, on F-Droid) we
  can study + draw from (license-permitting; Shelter is GPL-3.0).
- **Doesn't trip Play Protect / regulatory concerns**. Using sanctioned
  APIs in a sanctioned way.
- **Per-app freeze** — work-profile apps can be administratively
  disabled when not in use, denying them background execution. Major
  privacy win on top of isolation.
- **Suite integration is clean**: the sandbox host app provisions and
  manages the profile. Other suite apps (firewall, antivirus) can read
  the work-profile package list via existing user-aware APIs.

**Cons**:
- **One work profile per device** (Android limit). Can't have multiple
  isolated zones — there's "host" and "the contained zone," that's it.
  For the user's "I don't trust this app" use case, that's typically
  enough. For "I want N independent isolated environments," not.
- **No multi-instance app cloning beyond the work-profile copy**. You
  get host-instance + work-profile-instance of any given app. Not
  arbitrary copies.
- **Bound by what the work-profile API allows**. If we want a feature
  Android doesn't expose to the profile owner, we don't get it.
- **Some Samsung devices** ship Knox / Secure Folder which conflicts
  with third-party work-profile setup. UX gracefully degrades.

**Existing references**:
- [Shelter — GitHub](https://github.com/PeterCxy/Shelter), [F-Droid](https://f-droid.org/en/packages/net.typeblog.shelter/)
- [Insular](https://insular.app/) (Island fork, FOSS)
- Android's official [DevicePolicyManager docs](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)

**Verdict for our context**: Most pragmatic option. The OS does the
heavy lifting; we ship a host app + UX. Future-proof. Already proven
viable by a maintained FOSS reference. Doesn't require us to commit to
a multi-year Android-internals chase. Bounded by what the work-profile
API allows, which is "enough" for the stated goal but not "everything."

### Option C — Hybrid (Shelter-base + selective VirtualApp)

Start with Option B. Add Option A's multi-instance cloning ON TOP of
it, only for the specific case where the user wants two simultaneous
copies of the same app (ex: two messengers) and the work-profile slot
is already used.

**Pros**:
- Get Option B's stability for the 90% case.
- Get Option A's multi-instance for the 10% case.
- Phased: ship B first, add the A layer later if demand is real.

**Cons**:
- Doubles the surface to maintain. Two architectures, two sets of
  Android-version-churn issues, two failure modes.
- The A layer would still hit the Android 13+ hidden-API issues.
- Probably ends up being "the worst of both worlds" if the A layer
  adds maintenance burden disproportionate to its actual use.

**Verdict**: Worth holding in mind as a *path*, not a starting design.
If demand for multi-instance materializes after B ships, revisit. Not
a v1 plan.

## The recommendation (provisional)

**Option B — work-profile-based, Shelter-architecture**.

Reasoning:
1. Uses sanctioned Android APIs. Stays correct across Android versions
   without us chasing internals. This is a multi-year-of-suite-life
   bet — stability matters more than feature surface.
2. Real OS-level isolation, not user-space-mediation. The security
   property is what Google + every enterprise MDM has been investing
   in for a decade. We inherit that work.
3. Existing FOSS reference (Shelter) is mature, maintained, on F-Droid.
   We can learn from it without copying its UX.
4. Aligns with the suite's "use what Android provides correctly,
   don't reach for hidden features" posture — same posture that's
   served us through 7 apps already.
5. No regulatory / Play-Protect exposure.
6. Fits in the budget of "1-2 developer-months to ship v1," not "6
   months and ongoing chase."

**The cost of this choice** (worth being honest about):
- Multi-instance app cloning is gone. If the user needs two simultaneous
  copies of the same app, that's not on the table without revisiting.
- Only one isolated zone. "I want sandboxes for facebook, slack, and a
  random unknown app, all separate" is not what work-profile gives —
  all three would share the work-profile zone, with isolation from the
  host but not from each other.
- Limited to what the work-profile API exposes. We can't, e.g., snoop
  on an in-profile app's network at the kernel level (firewall app's
  per-app blocklist would still work via VpnService though).

Whether this is the right call depends on what the suite owner values.
If multi-instance cloning is a must-have, Option C. If "untrusted apps
can't see the host's data" is the actual goal, Option B nails it.

## What this doc does NOT decide

- The actual UX. Whether it's "an app drawer of contained apps,"
  whether the user installs apps into a sandbox via QR / link / .apk
  pick, whether freeze-when-idle is opt-in or default — all open.
- The threat model boundary. Specifically, do we trust the work-
  profile isolation against a state-level adversary? (Probably no, but
  most of the suite isn't designed for that threat anyway.)
- The data-sharing story. e.g., contained app needs file picker; do we
  expose host's vault-folder via SAF, or refuse all cross-profile data
  flow?

These are next-pass questions, after the architecture is picked.

## What I'd want to lay down once the architecture is chosen

If Option B, the v1 module looks like:

- `:sandbox` Gradle module — same hardening pattern as the other 7 apps.
- DeviceAdminReceiver subclass — Android requires this to provision a
  work profile.
- Work-profile provisioning flow — Compose UI walking the user through
  "add a contained zone."
- Contained-app management UI — install, freeze, unfreeze, uninstall.
- Suite hooks — let firewall see profile package list; let antivirus
  scan profile-installed APKs; let backups optionally back up
  profile-app data (carefully, per-app opt-in).
- Diagnostics surface (same pattern as the other 7 apps).
- ARCHITECTURE doc updated with the actual chosen design.

If Option A or C, the picture is bigger and the v1 milestone is much
further out — 3+ months instead of 1-2.

## Next step

I (the assistant in this session) am not going to choose. The
architecture call is yours. Read this, push back where it's wrong or
where you have priors I don't, sit with it, come back when you've
landed on a direction. Then I'll lay down the skeleton + scaffolding
for that direction.

If you want to push for Option A, I won't object — the maintenance
cost might be worth it for the feature surface, and you have judgment
I don't about whether the Android-version-churn risk is real for your
audience. I'm flagging the trade-off, not making the call.

---

## Addendum (corrections to the above after broader research)

The first draft of this doc treated Option A as "ended at VirtualApp2022,
Android 11 ceiling, ongoing chase." Owner pushback: there are sandbox
apps actively shipping at the Android 14 frontier, including some on
Play Store. Re-research found:

**Active sandbox / app-virtualization projects on Android 14+:**

- [SpaceCore](https://github.com/FSpaceCore/SpaceCore) — supports
  Android 6.0–14.0, multi-instance + isolation, Play Store deployable.
  **License: free SDK but not open-source.** Demo code on GitHub, real
  SDK is a closed-source release.
- [MultiApp / WaxMoon](https://github.com/WaxMoon/MultiApp) — AGPL-3.0,
  Android 6.0–14.0, last public update August 2023. Repo banner: "GitHub
  maintenance discontinued, see Play Store for official version."
  Effectively migrated to closed-source/commercial.
- [VirtualApp business version](https://github.com/asLody/VirtualApp) —
  claims Android 14 support; commercial.
- [Virtual Master / Clone Master](https://play.google.com/store/apps/details?id=com.clone.android.dual.space) —
  on Play Store, closed-source freemium.

**The pattern that matters for the suite specifically:**
The Android-14-capable forks of Option A are actively maintained but
have *migrated to closed-source / commercial models*. The FOSS lineage
(VirtualApp / VirtualXposed / community forks) tops out around Android
11. The community appears to have decided that the Android-13/14/15
hidden-API chase is expensive enough that you have to monetize it to
sustain it.

**This sharpens — but doesn't reverse — the recommendation:**

Option A is still tractable in 2026, but realistically only via:
- adopting a closed-source SDK (SpaceCore-class) — fits poorly with the
  suite's open-by-default + auditable-by-anyone posture, and creates a
  trust-asymmetry the suite is otherwise designed to avoid;
- or maintaining our own fork from the FOSS Android-11-ceiling base and
  porting forward — that's the multi-month chase the original draft
  warned about, and per the active-fork pattern, the work is real.

Option B (Shelter-class, work-profile-based) remains FOSS, on F-Droid,
maintained, sanctioned API, Android-version-stable. The recommendation
holds, but the more honest reason is:

> The Android-14-capable Option A implementations are commercial.
> Shipping a sovereignty-flavored FOSS suite on top of a closed-source
> sandbox SDK is a trust-asymmetry that breaks the suite's spirit. If
> we go A, we maintain our own — and that's the long expensive path
> the active commercial forks demonstrate the actual cost of.

**Acknowledgment**: the first draft was too dismissive of Option A by
implying the lineage had ended. It hasn't — it's continued, and the
people who continued it had to start charging. That's its own lesson
about the cost.

**Practical implication for the call**: if the suite owner is willing
to live with closed-source dependency in this one phase-2 module
(treating it as "we use a vendor for the container engine, like Brave
uses Chromium"), Option A becomes a 1-2-month integration project
instead of a 6+-month from-scratch project. If FOSS-throughout matters
(which has been the suite's posture so far), Option B remains the right
call by elimination, not just by preference.

Owner's judgment call.

**Other references found during this pass:**
- [Twoyi](https://github.com/twoyi/twoyi) — full Android-8.1 ROM
  virtualization. Heavier, different model from app-level virtualization.
- [Vectras-VM-Android](https://github.com/xoureldeen/Vectras-VM-Android) —
  QEMU-based, runs alternative OSes. Not really our use case.
- [Boxify](https://www.usenix.org/system/files/conference/usenixsecurity15/sec15-paper-backes.pdf) —
  USENIX 2015 academic paper on full-fledged app sandboxing for stock
  Android. Foundational reference; the technique it describes is what
  the VirtualApp lineage productionized.
- [Privacy Sandbox on Android](https://privacysandbox.google.com/overview/android) —
  Google's official initiative. Different scope (advertising / cross-app
  privacy), not what we mean by "sandbox" here, but worth noting it
  exists in the same vocabulary space.
