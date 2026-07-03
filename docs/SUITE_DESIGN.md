# Understory Suite — design + roadmap

> **V2 (2026-07-03): read the V2 section immediately below FIRST.** The
> coexistence doctrine is now normative, the roster/status table is corrected
> to audit reality (what actually works vs. what is alpha/broken), and the
> execution plan lives in `docs/design-v2/RELEASE-PLAN-V2.md`. Everything after
> the V2 section is the durable pre-V2 design record, preserved unchanged as
> the "why these choices" history — but where a pre-V2 statement conflicts with
> V2, **V2 governs.** In particular the pre-V2 table below said all seven apps
> "shipped"; the V2 audit found that means "code-complete v1 skeleton," not
> "shippable" — see the corrected table in the V2 section.

---

# V2 (2026-07-03) — NORMATIVE

## V2 coexistence doctrine (normative)

This section supersedes any earlier positioning language. It is the pasted,
normative form of `docs/design-v2/suite-coexistence.md §2` (source of truth for
edits). Every V2 design doc conforms to it.

> ### Coexistence doctrine
>
> **CD-1 · Complement, don't replace.** Every understory app must add value
> NEXT TO the app the user already runs for that purpose. If a feature's value
> depends on the incumbent being absent, disabled, or evicted, the feature is
> misdesigned. Reference-device incumbent set (Samsung SM-S948U, One UI):
> Tailscale (VPN), Bitwarden/1Password (autofill + password vault),
> Chrome/Brave (default browser), Aegis/Google Authenticator (TOTP), Samsung
> Secure Folder (file isolation), Play Protect (malware), Samsung
> Keyboard/Gboard (IME), Google One/Smart Switch (device backup).
>
> **CD-2 · Slot policy.** Scarce single-owner Android surfaces: the VPN slot
> (VpnService), the active autofill service, default-app roles (browser/SMS/
> assistant/home), the accessibility service, the notification-listener
> binding, device admin, and the usage-stats grant.
> (a) **The VPN slot is permanently VETOED.** Tailscale holds it. No
> understory feature may require, request, or be designed around VpnService —
> including "temporary" tunnels, DNS-redirect via a fake resolver route, and
> overlay-network transports (Yggdrasil/Lokinet-as-TUN). Packet-level engines
> may exist only as an explicitly-labelled, default-off "Standalone (no
> Tailscale)" mode, gated on detecting that no other VPN is active, and never
> as the primary verb.
> (b) **No feature may REQUIRE any other scarce slot.** A slot may be offered
> only as an explicit opt-in, and every opt-in must degrade gracefully: with
> the slot ungranted the app still delivers its core value, shows an honest
> status line naming who holds the slot ("Autofill: Bitwarden — passgen is in
> keyboard mode"), and renders no dead control and no re-enable nag against the
> incumbent.
> (c) **Multi-enable surfaces are the preferred delivery channels** — the IME
> list, the system share-sheet, and "Open with…" choosers are additive by
> construction. Never set an IME as default programmatically; never prompt for
> a default-app role; never call an API that evicts a slot's current owner.
> (d) **An incumbent holding or reclaiming a slot is a STEADY STATE, not an
> error.** UI renders it neutrally or positively (green "coexisting"), never as
> a fault to "fix". Specifically: the firewall must never render a
> Tailscale-took-the-slot event as "preempted — Re-enable".
>
> **CD-3 · Incumbent-interop policy (import AND export).** Wherever the
> incumbent category has an established interchange format, understory apps
> speak it in **both directions**. Minimum format set by category:
> - Passwords: Bitwarden CSV **and** Bitwarden JSON (`items[].type==1`), Google
>   Password Manager CSV, Proton Pass CSV/JSON — import and export.
> - TOTP: Aegis JSON (plain; encrypted scrypt slots when feasible),
>   `otpauth://` URI lists, `otpauth-migration://` (import), 2FAS JSON —
>   import; `otpauth://` list + QR render — export.
> - Files: plain bytes via SAF (`ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT`)
>   — universal, no lock-in, both directions.
> - Backups: the suite `BackupEnvelope` is the one at-rest export format; a
>   passphrase/recovery-key path makes it restorable off-device.
>
> **An app that ingests a secret class but offers no user-reachable export of
> it is a roach motel and does not ship.** Export must be a real, reachable UI
> action — not a dead adapter class.
>
> **CD-4 · Honest-UI policy.** (a) Zero dead controls: no button/switch/toggle/
> picker whose action cannot complete on this build of this device — remove,
> disable-with-reason, or eng-gate it. (b) Zero capability overclaim: UI copy,
> notifications, manifest comments, READMEs, roadmap rows, launcher labels, and
> suite capability beacons may claim only what the shipped code does today.
> (c) Failure honesty: every silent dead-end gets a visible, truthful message.
> (d) Status honesty: primary status surfaces never overstate active
> enforcement/protection; a green derived from an unreadable setting degrades to
> "unknown". (e) Cleanup honesty: clipboard auto-clear / "session cleared" /
> shred claims must match the implementation's real guarantees, including
> behavior across process death and OEM clipboard policy.
>
> **CD-5 · Names are claims.** Launcher labels, store names, and capability
> beacons are subject to CD-4(b). A name that asserts a capability the app lacks
> ("antivirus" for a static auditor; "firewall" for an advisor that blocks
> nothing) is an overclaim; a name that collides with the incumbent it
> complements ("aegis" beside Aegis Authenticator) contradicts CD-1 by
> construction.

## V2 app roster / status — corrected to audit reality

The pre-V2 table (further down) marked every app "shipped." The V2 audit
(`docs/audit-v2/`) found that meant "v1 code-complete skeleton," and that each
app has ship-blocking gaps — most commonly **one-way data flow** (secrets check
in, nothing checks out) and **claims ahead of code**. Corrected status:

| # | app | codename | V2 store face | Honest v1 state | Top ship-blocker(s) |
|---|-----|----------|---------------|-----------------|---------------------|
| 1 | passgen | passgen | **Understory Keys** | ALPHA — generator works; vault is a roach motel; generate-without-record is a lockout trap | no export; fictional reset; receipt ledger missing |
| 2 | aegis | aegis | **Understory OTP** | ALPHA — **generates WRONG codes** for non-SHA1/6/30; no export; name collides with Aegis Authenticator | OTP correctness; export; IME unviable under lock-on-leave; rename |
| 3 | firewall | firewall | **Understory Net Audit** | BROKEN under doctrine — enforcement core needs the vetoed VPN slot; evicts Tailscale + nags to evict it back | reposition to observe/advise + guarded Standalone; drop VPN-core UI |
| 4 | backups | backups | **Understory Backup** | ALPHA — envelope encrypt works; **no restore decoder** for `.usbs`; scheduling impossible; orchestration is a stub | build restore; framing fix; honest scheduling; beacon de-overclaim |
| 5 | browser | browser | **Understory Safe View** | ALPHA — hardening is real; **no doorway (share-target) or exit**; "Clear session" lies; I2P over-claims | intake interstitial; honest Clear; proxy shrink |
| 6 | antivirus | antivirus | **Understory APK Check** | ALPHA — isolated parser is sound; **abuser rules never fire**; empty KnownBad; false-green Play Protect; static, not "real-time" | real abuser detection; signed blocklist; kill false-green; rename |
| 7 | vault-folder | vaultfolder | **Understory File Vault** | ALPHA — **export crashes** (the one confirmed hard crash); no viewer; no recovery; deposit auto-encrypts vs a claimed confirm | crash fix; recovery; deposit confirm; viewer |
| 8 | sandbox | — | — | **phase 2** | vfone-class container — see below |
| 9 | (defensive toolkit) | — | — | **phase 3** | runtime observation in sandbox — see below |

Suite-wide V2 defects that cut across all seven (`audit-v2/SUITE.md`): biometric
re-enrollment bricks all four vaults (three of four have no recovery UI); four
separate cloned vault engines; seven hardcoded-hex dark themes with 1–2-entry
`strings.xml`; three capability beacons that claim powers nobody delivers;
main-thread crypto/IO across five apps. All are addressed by the shared-infra
wave (`design-v2/RELEASE-PLAN-V2.md §1`).

**V2 execution plan:** `docs/design-v2/RELEASE-PLAN-V2.md` (waves + per-app
FIX/REDESIGN/DROP checklists + operator decisions). **V2 definition of done:**
`docs/RELEASE_BLOCKERS_V2.md` (supersedes the pre-V2 `RELEASE_BLOCKERS.md`).
**V2 per-app + shared designs:** `docs/design-v2/*.md`.

---

# Pre-V2 design record (durable history — V2 governs on conflict)

**Status (pre-V2, retained as history)**

**7 of 9 target apps ship.** Final 8th (sandbox) lands in phase 2; final 9th (defensive toolkit) lands in phase 3.

> NOTE (V2): "shipped" below means v1 code-complete, not shippable — see the
> corrected V2 roster above. The role descriptions here are the pre-V2 framing;
> several are overclaims the V2 store faces correct ("firewall"→Net Audit,
> "antivirus"→APK Check, the VpnService gate→observe/advise + guarded Standalone).

| # | app | status | role |
|---|-----|--------|------|
| 1 | passgen        | shipped | identity / password vault + autofill IME |
| 2 | aegis          | shipped | OTP/TOTP/HOTP vault + IME |
| 3 | firewall       | shipped | VpnService outbound traffic gate |
| 4 | backups        | shipped | encrypted envelope tool (AES-GCM + Argon2id) |
| 5 | browser        | shipped | hardened WebView, JS-off-default, https-only |
| 6 | antivirus      | shipped | static APK + permission auditor |
| 7 | vault-folder   | shipped | encrypted-at-rest file vault |
| 8 | sandbox        | **phase 2** | vfone-class container — see below |
| 9 | (defensive toolkit) | **phase 3** | runtime observation in sandbox — see below |

The 9th-app slot in the original design (mdm-local) is rolled into
firewall as a settings screen, or skipped. The "messenger" slot is
intentionally not built — users install Briar / SimpleX / Session /
Matrix / etc. inside the sandbox; the suite composes with existing
sovereign-aligned messengers rather than reimplementing one.

## Phase 2 — sandbox (#8)

The 8-app target was decided after architectural discussion concluded
that **a root-capable guest container running on an unrooted host** is
the strongest rootless isolation model achievable on Android today
(stronger than Work Profile, which shares kernel + filesystem with the
main user). Reference implementations: VPhoneGaga, VMOS, VirtualXposed.

**The model**:
- Bundled AOSP userspace runs inside a user-namespace + chroot inside
  the suite app's own UID
- Guest gets fake CAP_SYS_ADMIN within the namespace — Magisk works
  inside, host integrity untouched
- Compromise inside the guest stays inside the guest
- Network egress optionally routes through firewall
- Wipe = delete the guest; host unaffected

**Why it ships in phase 2 rather than now**: 200–500 hours of focused
build work (fork VirtualXposed, modernize for Android 13–15, bundle
minimal AOSP image, integrate firewall + suite registry). Plus 20–40
hours/month maintenance ongoing as Android security patches land. Not
fittable into the same iteration as the 7-app ship.

**Why it doesn't ship as a Work-Profile-only MVP**: a placeholder MVP
becomes a permanent answer if phase-2 work gets perpetually deferred;
shipping the weaker isolation as "the answer" misrepresents the
sovereignty thesis. Cleaner to reserve the slot, ship 7 strong, and
land sandbox-as-vfone-class when ready.

**The keystone property — vault-bridged sandbox**: the sandbox isn't
isolated *from* the host's other suite apps; it composes *with* them
through the encrypted envelope format. Sandbox state can be exported as
a `BackupEnvelope` (the format that already ships in `:common-backup`),
SAF-written to the host's vault-folder or backups, and re-imported on
the same or another device by anyone holding the passphrase.

This gives layered security properties no individual layer provides:

- **Sandbox compromise doesn't lose data** — encrypted exports survive
  in the host's vaults; tear down the sandbox, restore from envelope,
  same state minutes later.
- **Host compromise doesn't read sandbox data** — even host root sees
  only AES-GCM ciphertext; without the passphrase it's opaque.
- **Cross-device portability** — the encrypted blob moves on USB, SAF,
  any channel. Same passphrase, same vault, different device.
- **Multiple sandbox contexts on one device** — different sandboxes,
  different vault folders, different passphrases. Each is its own
  sovereign domain on the same hardware.
- **No layer fully trusts another** — sandbox has its own root, host
  has its own integrity, vault has its own crypto, each carries keys
  the others don't hold. Compromise of any one layer does not cascade.

The architecture for this fell out for free. `:common-backup` shipped
at `0b905f9` with `BackupEnvelope.write` / `parse` and the
`AesGcmPassphraseCodec`. Phase 2 sandbox uses the same primitives the
backups and vault-folder apps already use. We didn't pre-engineer the
integration — it composed because each piece was built to honest
contracts. That kind of architectural alignment is the signal the
design is correctly shaped.

**License note**: sandbox APK will be GPL-3 (forced by VirtualXposed
ancestry). Other suite apps stay MIT/Apache. The Tivoization clause
means we can't cert-pin the bundled AOSP against user modification,
which is *correct* — the user owns the guest.

## Phase 3 — defensive toolkit (#9)

If sandbox phase-2 lands and real users want behavioral observation
tools, the 9th-app slot completes the suite as a **defensive (blue)
sovereignty toolkit** — the inverse polarity of red-team offensive
frameworks like Kali NetHunter, with the same architectural pattern
(Magisk + Vertex/LSPosed-class hooks + curated modules) inverted to
serve *observation and defense* rather than attack.

**The model**:
- Thin host-side orchestrator app + curated library of Magisk + Vertex
  modules deployed inside the sandbox
- Modules are defensive — they observe app behavior and surface findings
  to the user; they don't attack other systems
- Each module is independently enable/disable per app inside the sandbox

**Curated module set (initial)**:
- `network-tap` — log every network call any sandbox app makes (vs.
  the firewall's network-layer filtering, this is method-call level)
- `permission-monitor` — hook runtime permission grants to log when an
  app actually exercises sensitive APIs
- `tracker-detector` — flag known tracking SDKs (Facebook, AppsFlyer,
  Adjust, etc.) at the call site
- `clipboard-watcher` — log clipboard access; catch silent exfiltration
- `storage-monitor` — log file system access patterns; catch
  unauthorized data harvesting
- `cert-pinning-bypass-detector` — surface CA-trust anomalies suggesting
  MITM attempts on tested apps
- `process-snapshot` — capture full app state for offline forensic
  analysis

**Composition with existing apps** — the keystone of why this completes
the suite as a defensive toolkit:

| Layer | App | What it sees |
|---|---|---|
| static, host | antivirus | what an APK *declares* it can do |
| network, host+sandbox | firewall | what an app *contacts on the network* |
| **runtime, sandbox** | **9th defensive toolkit** | **what an app *actually does* internally** |

Three observational layers; no single one substitutes for the others.
The 9th app gives the suite behavioral coverage that complements (not
duplicates) what antivirus and firewall already provide.

**Architectural property — sandbox-confined elevation**:
The 9th app elevates capabilities (root, hooks, kernel-adjacent
observation) **entirely within the sandbox guest**. The host has zero
elevation surface contributed by this app; the only elevation surface
on the device is the opt-in sandbox itself. Host stays vanilla — calls,
SMS, daily-use apps work unmodified — and treats the suite as
*observational from a clean position* rather than incurring elevation
cost for the privilege.

This is why the 9th genuinely completes the suite rather than expanding
its surface: it adds *runtime visibility* without adding *runtime
exposure*.

**Phase placement**: only after phase-2 sandbox ships and is real-world
proven. Building the 9th before the 8th is real means orchestrating
something that doesn't yet exist. Realistic effort: ~2–4 months focused
work on the orchestrator + curated module library, after sandbox
lands. Ongoing maintenance ~40–80 hrs/month for the module library as
Android version churn shifts the hook surface.

**License**: GPL-3 (forced by Magisk + Vertex ancestry). Same situation
as sandbox.

**Naming**: open. Working candidates: `lab`, `scope`, `inspector`,
`warden`, `lookout`. Lean toward `lab` (matches the "in the sandbox"
metaphor) or `scope` (literally what it does — sees through). Defer
final choice until the app is being built.

## Why the suite stops at 9

After phase 3 the sovereignty taxonomy is complete:

- **Identity**: passgen
- **Authentication**: aegis
- **Network**: firewall
- **Backup**: backups
- **Web**: browser
- **Static defense**: antivirus
- **Storage**: vault-folder
- **Isolation**: sandbox
- **Behavioral defense**: 9th (defensive toolkit)

Each domain has its sovereign answer. Past 9 the additions become
either category-contradictory (custom-host-rooting violates the
rootless thesis) or category-duplicative (writing our own messenger
when the sandbox + user-installed sovereign messengers cover it).
The right shape is to stop at 9 and let any further apps be earned
by demonstrated user need rather than count-filling.

## Why this document still exists

passgen (#1), aegis (#2), firewall (#3), backups (#4), browser (#5),
antivirus (#6), vault-folder (#7) all ship as of this commit. Their
detailed design notes below are kept as reference material — some
sections describe pre-ship plans that may differ in specifics from
what shipped, but the *shape* of each app's design rationale is
preserved here as the durable record of *why these choices over
alternatives*.

Each app's section retains:
- **Pitch** — one paragraph. What it is, who it's for.
- **Threat model** — what it specifically defends against.
- **Permissions** — the minimum-justified set, with explicit reasoning.
- **Core UI flows** — text sketch of the screens, no mockups.
- **Cross-app interactions** — how it composes with the suite.
- **Distribution constraints** — Play Store / F-Droid friction.
- **Open questions** — what we still need to decide.

Constraints that apply to every app, not repeated below:
- Rootless. No Shizuku, no Xposed, no signature-spoof.
- In-bounds. Public Android APIs only; passes Play-policy review.
- Vanilla phone. No mandatory dev-options toggles.
- Local-first. Network features always opt-in.
- Same hardening posture as passgen/aegis: FLAG_SECURE on every
  activity, BiometricPrompt-gated unlock, debuggable=false in debug,
  full comms-permission strip, Tamper + SuiteAttestation at every
  entry point.
- Same crypto envelope: AES-256-GCM + Argon2id + Keystore-bound
  device-credential. Hot path for everyone.

Apps below are listed in shipping priority (most leveraged first).

---

## Cumulative emergence — the tier-unlock ladder

Every suite app is **complete standalone**. Installing more apps from
the suite **dynamically unlocks** additional features in apps that were
already installed — no restart, no reinstall. Detection is reactive:
each app subscribes to `PACKAGE_ADDED` / `PACKAGE_REMOVED` and re-runs
`SuiteCapabilityRegistry.snapshot(ctx)` to learn what's currently
present and verified. Newly-unlocked surfaces appear in the UI on the
next render; newly-removed surfaces hide gracefully.

### The two semi-centers

Two apps are "centers" by virtue of position, not by code path. Nothing
*depends* on them being installed; everything *strengthens* when they
are.

- **passgen — the keys/identity center.** Owns the master vault, owns
  the suite cert pin location, hosts the autofill IME. Other apps that
  detect passgen can derive per-app keys from the master vault, gate
  destructive actions on master-unlock, and push generated secrets into
  the same IME injection path.
- **firewall — the runtime/policy center.** Owns the only VpnService
  slot. Other apps that detect firewall register filter rules with it
  via signed Intent — DNS blocklists, per-host allow/deny, kill-switch
  on vault-unlock failure.

A repackaged "passgen" or "firewall" with a different signing cert
contributes zero capabilities — `SuiteCapabilityRegistry` cert-checks
every peer before granting any uplift.

### Mechanism

`SuiteCapabilityRegistry` (in `:common-security`) discovers peers via
each app's exported `SuiteCapsProvider` (a signature-protected
ContentProvider that returns one row: `{ version, cert_sha256 }`). The
*meaning* of `version` is local knowledge — every consumer hard-codes
its own `KNOWN_PEERS: Map<packageName, Map<version, Set<Capability>>>`,
so a peer cannot self-grant capabilities it wasn't shipped with. New
peer = invisible feature-wise until KNOWN_PEERS is updated, even if
the peer's beacon claims it.

The registry returns a `Snapshot` with:
- `capabilities: Set<SuiteCapability>` — union across verified peers.
- `tier: SuiteTier` — `STANDALONE / PAIR / TRIPLE / MESH` based on
  verified-peer count.
- `peersWith(c)` — which peers offer a given capability (for routing).

### The ladder

**Tier 1 — STANDALONE (1 app installed).**
Each app's full standalone feature set. Nothing assumed.

**Tier 2 — PAIR (2 verified peers, including self).**
Pairwise unlocks light up. Examples:
- *passgen + aegis* → cross-vault sealing. Vault key wrapped under
  both vaults' KDFs; loss of either alone doesn't open the other.
- *passgen + firewall* → kill-switch on auth failure. 3 wrong vault
  attempts → firewall blocks all suite-app egress until manual
  unblock from firewall UI.
- *aegis + firewall* → step-up auth on untrusted networks. Firewall
  flags the network; aegis OTP required to relax firewall rules.
- *backups + any-app* → that app gains an "Encrypted export" UI
  surface that hands its `BackupAdapter` payload to backups.

**Tier 3 — TRIPLE (3–4 verified peers).**
Suite-wide policies become coherent. Examples:
- *passgen + aegis + firewall* → "fortress mode" toggle in any of
  them. Every vault unlock requires master + OTP, every network
  egress is default-deny, screen-on idle locks all vaults at 60s
  instead of 5min.
- *passgen + aegis + messenger* → message-vault keys derived from
  both vaults. Messenger refuses to start without both peers.
- *firewall + browser + antivirus* → browser delegates third-party
  domain decisions to firewall, and antivirus pre-scans any
  download before browser writes to disk.

**Tier 4 — MESH (5+ verified peers).**
Quorum attestation viable. Examples:
- Every app verifies *every* peer's cert. If more than one peer
  fails attestation, the local app refuses to operate (catches a
  single compromised sibling, not just a corrupted host).
- `LOCAL_POLICY` (mdm-local) becomes meaningful: a single policy
  surface that pushes idle-lock / step-up / network-stance
  decisions to every peer at once.

### Adding a new capability or peer

1. Add the value to `SuiteCapability` (in `:common-security`).
2. Update `SuiteCapabilityRegistry.KNOWN_PEERS` in *every consumer
   that should see the new power* — usually all of them.
3. The peer ships a `SuiteCapsProvider` with the new
   `providedVersion` (or unchanged version + the new capability,
   depending on whether the change is backwards-compatible).
4. Document the tier-unlock entry above so future Claude / future
   you knows what to expect.
5. Rebuild + push every consumer simultaneously — staggered rollout
   means peers attest a version no consumer recognizes, which
   degrades gracefully (zero capabilities for that peer until the
   consumer also updates).

### Honest tradeoff

A capability registry is one more queryable surface per app — small
attack surface increase, mitigated by `signature` protectionLevel and
the local-knowledge-only capability mapping. The bigger ongoing cost
is *coordinating tier definitions*: once we ship tier-2 features that
depend on a peer, removing that peer must degrade gracefully (not
crash), which we pay for in adapter code and cross-app integration
tests. We accept this — the alternative (a single hub app or a server-
mediated coordinator) is worse for sovereignty.

---

## #3 firewall — VPN-slot outbound traffic gate (Phase B → ship)

**Status:** scaffolded. Phase B turns it functional.

### Pitch
Combined firewall + VPN. Owns Android's `VpnService` slot — captures
all outbound traffic via a local tun interface, applies per-app rules,
overrides DNS, blocks domains, logs activity. The user picks: allow
everything, allow none, or per-app fine-grained. Like NetGuard but
suite-aligned (same crypto, same Tamper, same device-bound posture).

### Threat model
- Apps exfiltrating data (telemetry SDKs, ad networks, malicious
  keyboards calling home).
- Untrusted networks (coffee-shop wifi MITM) — VPN routes traffic
  through an outbound tunnel of the user's choosing or just to local
  loopback for filtering.
- DNS-level tracking — block ad-tech, fingerprint-aggregator, telemetry
  resolvers via blocklist.
- Lateral movement attempts — block inbound connections to apps with
  listening sockets.

### Permissions
- `INTERNET` — required for the VPN tunnel itself.
- `ACCESS_NETWORK_STATE` — required to monitor network changes (wifi
  vs cellular vs offline).
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — VpnService
  must run as a foreground service when active.
- `POST_NOTIFICATIONS` — only to expose a persistent notification
  while the VPN is active (Android requires it for foreground services).
- Everything else stripped. Especially the SMS/CAMERA/LOCATION/etc.
  block — firewall has no business with any of those.

### Core UI flows
1. **First-run setup** → "This app will install a VPN profile. The VPN
   stays on-device — no traffic leaves your phone except as you
   configure. Tap to start." → Android's standard VpnService consent
   dialog → BiometricPrompt for our own gate → done.
2. **Main screen** → status row (VPN ON/OFF, packets/sec, blocked %),
   per-app toggle list (every installed app, allow/deny/wifi-only/
   mobile-only).
3. **DNS settings** → toggle DNS override on/off, dropdown of presets
   (NextDNS, Quad9, Cloudflare, Google, custom), blocklist URL imports
   (StevenBlack, EasyList, OISD).
4. **Activity log** → recent connections per app: timestamp, dest,
   port, allowed/blocked, bytes. Local-only, ring-buffer in encrypted
   storage. Clearable.
5. **Per-app rules screen** → tap an app → fine-grained: allow these
   destinations only / block these only / log everything / etc.

### Cross-app interactions
- **passgen** — firewall blocks egress for clipboard scrapers / a11y
  services / etc. that might leak passgen-generated values from other
  apps. Passgen never has INTERNET; firewall is what stops *other*
  apps from leaking.
- **aegis** — same. Authenticator leaks happen via other apps.
- **suite-wide** — when firewall is installed, the BlackArch defense
  matrix gains 4-MITM and 8-clipboard rows because the firewall
  blocks the data-egress side that those threats depend on.

### Distribution constraints
- Play Store: VpnService apps face increased scrutiny. Doable but the
  policy review takes weeks. F-Droid is friendlier.
- The combined firewall+VPN model is unusual on Play (most firewall
  apps split into "VPN" and "filter"). We commit to the combined
  shape because Android only has one VPN slot.

### Open questions
- **Blocklist import format** — JSON / hosts-file / EasyList syntax /
  all three? Probably hosts-file for max compat.
- **Per-app rules persistence** — encrypted vault.bin like passgen, or
  plain SharedPreferences (since rules aren't secrets, just
  preferences)? Probably plain — they're not credentials.
- **Activity log retention** — last 1000 events ring-buffer, or last
  24 hours, or user-configurable?

---

## #7 backups — local-first encrypted backup orchestrator

**Status:** planned. High value because every other app needs export.

### Pitch
One app that orchestrates encrypted backups for every suite member.
Each suite app exposes a "backup-export interface" (just a content
provider with a signature-permission); backups iterates them,
encrypts the result, and writes to a user-chosen destination — USB
stick, Syncthing folder, self-hosted endpoint, paper QR for tiny vaults.
Schedule on a timer. Restore is the inverse.

### Threat model
- Lost device — backups make recovery possible without trusting any
  cloud.
- Server compromise — backup file is encrypted; server admin (you)
  cannot read your own backups.
- Backup file theft — file alone is useless without the recovery
  passphrase or HOTP code.
- "Harvest now, decrypt later" — backup format adds ML-KEM-1024
  hybrid layer so a future CRQC can't break harvested files.

### Permissions
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (only if user opts to back
  up media via the SAF picker). Default off.
- `POST_NOTIFICATIONS` — backup-completed indicator.
- `WAKE_LOCK` — for scheduled backups that should not be killed mid-flight.
- `RECEIVE_BOOT_COMPLETED` — to re-arm the alarm on reboot.
- Otherwise comms-stripped. Backups doesn't need network — it writes
  to user-picked destinations via SAF, which can be a Syncthing
  folder or a USB drive without backups itself touching the network.

### Core UI flows
1. **Main** → list of installed suite apps with backup status: last
   backup timestamp, size, destination.
2. **Schedule** → off / hourly / daily / weekly + which destinations
   to write to.
3. **Destinations** → list of registered destinations: SAF URI for
   Documents/Drive/USB, Syncthing folder, etc. Each carries its own
   passphrase.
4. **Restore** → pick a backup file → enter passphrase → confirm
   destination apps to restore into → BiometricPrompt → done.
5. **Suite manifest** → preview what's in a backup file before
   restoring (which apps, which date, which device).

### Cross-app interactions
- Discovers suite siblings via `<queries>` and the `SuiteAttestation`
  list. Each sibling exposes a `BackupProvider` ContentProvider with
  signature-level permission `com.understory.suite.BACKUP`. Backups
  binds to each, calls `export()`, gets back an opaque ciphertext blob.
- Restores by inverse: pick file → match contents to sibling apps by
  package name → for each sibling, hand it the ciphertext via
  `restore()`.

### Distribution constraints
- F-Droid: trivial.
- Play Store: doable. The `RECEIVE_BOOT_COMPLETED` permission requires
  policy declaration but is routine.

### Open questions
- **Backup passphrase per destination, or one global?** Per-destination
  is more flexible (different recipients) but more passphrases to
  remember. One global is simpler. Probably per-destination.
- **HOTP-gated recovery** — when restoring, gate by HOTP code from
  aegis. Aegis stores the suite's recovery HOTP secret as one of its
  entries. Lost device + new install + aegis re-set-up = restore via
  HOTP code typed.
- **Format versioning** — v1 = AES-only, v2 = ML-KEM hybrid (the
  Stage 2C-2 work). Backups picks v2 if the destination is offline-
  sketchy (cloud, USB) and v1 if local-LAN.

---

## #8 browser — Chromium-aligned hardened browser

**Status:** planned. Useful for any password-flow or 2FA-fill flow.

### Pitch
Standalone Chromium-based browser, Cromite-aligned. Strict CSP defaults,
no JIT, no fingerprinting surface. Optionally settable as the *system
WebView provider* for users who toggle dev-options — works as standalone
otherwise. Same suite hardening posture: FLAG_SECURE, Tamper,
SuiteAttestation. The key suite-feature: tight integration with passgen
autofill and aegis OTP-fill, so password-form flows don't have to
context-switch out of the browser.

### Threat model
- Browser-side exploitation (BeEF, malicious JS, XSS).
- Fingerprinting — canvas, font, timezone, audio, plugin-list.
- Tracking — third-party cookies, beacons, web bluetooth.
- Drive-by APK install via deceptive download.

### Permissions
- `INTERNET` — required, obviously.
- `ACCESS_NETWORK_STATE` — for offline-detection.
- `READ_MEDIA_IMAGES` / VIDEO / AUDIO (downloads).
- `POST_NOTIFICATIONS` — download-completed.
- Everything else stripped. NO microphone, NO camera, NO location.
  Explicitly: web sites cannot escalate to those because we don't
  have them. (If a user wants a webcam-using site, they use a
  different browser. Sovereign trade-off.)

### Core UI flows
1. **Address bar** → type URL or search → load.
2. **Tabs** → Chrome-style horizontal scroll, can be incognito.
3. **Settings** → DNS-over-HTTPS toggle, blocklist enable, JS strict
   mode, fingerprint-randomization, default-deny third-party cookies.
4. **Per-site permissions** → camera/mic/location are *always denied*
   (we don't have the perms anyway). Notifications can be granted
   per-site.
5. **Password fields** → autofill triggers passgen suggestion; OTP
   fields trigger aegis suggestion. Same Android Autofill API plumbing
   we already wired in passgen.

### Cross-app interactions
- **passgen** — autofill provider already supports the `<input
  autocomplete="new-password">` and `current-password` attributes on
  the Cromite fork. Browser doesn't ship its own autofill — defers to
  the system, which is passgen on this device.
- **aegis** — same for `<input autocomplete="one-time-code">`. When
  the browser detects an OTP field, the system autofill picker
  includes aegis if it's installed.
- **firewall** — browser's outbound traffic is filtered same as any
  other app's. The blocklist installed in firewall blocks ad/tracker
  domains at the network layer; browser's CSP enforcement happens at
  the rendering layer.

### Distribution constraints
- Cromite is GPL-3. Our browser fork must be GPL-3. The rest of the
  suite is MIT/Apache; browser is the only GPL piece.
- Play Store does not allow Chromium-based browser apps that
  significantly modify Chromium without proper labeling. F-Droid is
  the realistic distribution.
- Significant build-system work — Chromium build is gigabytes and
  takes hours on first build. We'd ship as pre-built APKs only.

### Open questions
- **WebView system-provider toggle** — yes/no/optional? Probably
  optional. Most users won't bother; that's fine.
- **Sync** — Cromite supports Chromium sync. We disable it (sovereignty:
  no Google account dependency).
- **Cromite vs. Mull (Firefox-based) vs. fork-from-scratch** —
  Cromite is the right answer (most-active fork, well-maintained,
  closest to upstream Chromium minus the bad parts). Mull is good but
  Firefox-based, which is a different threat model. Fork-from-scratch
  is years of work.

---

## #4 vault-folder — encrypted-at-rest folder

**Status:** planned. Same shape as passgen Vault but for files.

### Pitch
A "secure folder" you open with biometric. Drop files in; they're
encrypted at rest with the same Keystore-bound device-credential key
the rest of the suite uses. Works without root, without Samsung's
"Secure Folder" work-profile dependency. Honest sell: encrypted folder,
biometric-gated open.

### Threat model
- Lost-but-still-encrypted device — the file system is decryptable
  only by the user's logged-in session. Vault-folder adds an extra
  per-app layer.
- Borrowed phone — biometric gate prevents casual snooping.
- App-data-extraction attacks — same `allowBackup="false"` + scoped
  storage as passgen.

### Permissions
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`
  *only when explicitly importing*. We use ACTION_GET_CONTENT (no
  permission) wherever possible.
- `POST_NOTIFICATIONS` — completion of long imports.
- Otherwise comms-stripped.

### Core UI flows
1. **First-run** → BiometricPrompt-gated vault creation, same as
   passgen.
2. **Folder list** → entries: filename (or fake name if user
   renamed), size, type icon (only). NO previews unless explicitly
   tapped.
3. **Add file** → SAF picker → pick file → app encrypts and stores
   in app-private storage; original file optionally deleted from
   source location (user-choice).
4. **Open file** → BiometricPrompt → temporary decryption to
   ContentProvider URI → handed to user-picked app via ACTION_VIEW.
5. **Export file** → BiometricPrompt → SAF picker → write decrypted.

### Cross-app interactions
- **backups** — vault-folder exposes `BackupProvider` like the others.
  Files in the folder are part of the suite-wide backup.

### Distribution constraints
- F-Droid: easy.
- Play Store: easy. Encrypted-folder apps are routine.

### Open questions
- **Scoped storage**: how do we handle large files (videos)? Encrypt-
  in-place vs. encrypt-then-copy. Encrypt-then-copy is safer (atomicity)
  but doubles disk usage. Encrypt-in-place is faster but riskier.
- **Filename obfuscation** — should the on-disk filename be a UUID,
  or the encrypted-name? If UUID, metadata leak (count, sizes) but
  not names. If encrypted-name, attacker who has filesystem access
  can't infer file contents from names.

---

## #6 messenger — SMS/RCS reader with spam quarantine

**Status:** planned. Specialized; lower priority for general users.

### Pitch
Read SMS/RCS messages locally. Carry a user-curated allowlist of
senders. Anything from an unknown number → quarantine pane (still
visible, just flagged). Supports the standard `notification.app`
intent integrations so you can use it as your default messenger.

### Threat model
- SMS-based phishing (smishing).
- Account-recovery code interception (a malicious app reading SMS).
- Sender impersonation.
- Premium-rate SMS exfil (a different app sending SMS without the
  user noticing).

### Permissions
- `READ_SMS` — required. Justified for the app's purpose.
- `RECEIVE_SMS` — required. Same.
- `SEND_SMS` — only if the user makes messenger their default; gated.
- `READ_PHONE_NUMBERS` — to display the user's own number for
  account-recovery flows.
- `POST_NOTIFICATIONS` — incoming-message notification.
- Otherwise comms-stripped.

This is the only suite app that requires SMS perms.

### Core UI flows
1. **Inbox** → conversations list, allowlisted senders at top,
   quarantine at bottom (collapsible).
2. **Conversation view** → standard message bubbles. Sender's
   allowlist status shown as a colored dot.
3. **Allowlist management** → add/remove senders by number or
   contact-name lookup.
4. **Quarantine pane** → unfiltered view of unknown-sender messages,
   with "Allow this sender" / "Block permanently" actions.

### Cross-app interactions
- **passgen** / **aegis** — when an SMS contains an OTP, messenger
  auto-detects and offers to copy to clipboard with EXTRA_IS_SENSITIVE
  (same Clipboard helper passgen has). Or hand off to aegis if the
  user wants to add it as an entry.
- **firewall** — irrelevant; SMS is a different transport layer.

### Distribution constraints
- Play Store: SMS apps need to be the user's default messenger to
  qualify, which requires Play to declare you a default-SMS-handler.
  Possible but the review process is involved. F-Droid easier.

### Open questions
- **RCS support** — Google's RCS is not an open API. We can read
  RCS as plain SMS in the inbox (Android exposes that), but rich
  features (typing indicators, read receipts) require Google's RCS
  framework which is locked behind Google Messages app dependency.
  Probably skip and document.
- **Default messenger replacement** — yes/no? If yes, gain SMS-send
  capability. If no, read-only mode. Probably default-no with a
  toggle to opt in.

---

## #5 antivirus — heuristic real-time protection

**Status:** planned. Lower priority because "antivirus on Android" is
genuinely hard.

### Pitch
Watch app installations and modifications. Compare against published
bad-actor signature lists (similar to AV-databases). Hash-check
downloaded APKs *before* install. Heuristic checks: signs of
runtime-hooking frameworks, signs of accessibility-service abuse,
known-bad permission combinations.

### Threat model
- Side-loaded malicious APKs.
- Apps changing behavior post-install (signature-changed updates).
- Cross-app abuse patterns (one app gains accessibility, then another
  reads its outputs).

### Permissions
- `QUERY_ALL_PACKAGES` — antivirus needs full app visibility. Justified
  for this app's purpose. **Only suite app that requests this.**
- `RECEIVE_BOOT_COMPLETED` — re-arm the install-watcher on reboot.
- `POST_NOTIFICATIONS` — alert on detection.
- Otherwise comms-stripped. Signature lists are bundled in the APK
  (or imported via SAF — never network-fetched without user consent).

### Core UI flows
1. **Status screen** → green/yellow/red, count of installed apps
   reviewed, list of flagged apps with their flag reason.
2. **App detail** → full review of an app's permissions, signing
   cert, install source, post-install modifications, accessibility
   bind status.
3. **Signature import** → SAF picker → import a signature list (we
   don't auto-fetch).
4. **Heuristic settings** → toggle each heuristic (root-detection
   sensitivity, accessibility-abuse sensitivity, etc).

### Cross-app interactions
- **firewall** — antivirus flags an app, firewall offers a one-tap
  "block this app's network." Cross-suite intent integration.
- **mdm-local** — antivirus flag → mdm-local policy violation.

### Distribution constraints
- Play Store: antivirus apps face heavy scrutiny under their security
  category. Possible but slow review. F-Droid easier.
- "Antivirus" naming carries marketing baggage — be careful about
  the framing. Honest pitch is "heuristic posture review", not
  "we'll save you from malware."

### Open questions
- **Signature source** — bundled in the APK (offline, ages with the
  release) or user-import (SAF). Probably user-import as primary,
  bundled minimal list as fallback.
- **Real-time vs. on-demand** — real-time install-watcher is the
  obvious feature; on-demand audit-scan-of-installed-apps is the
  honest add. Both.
- **What about Knox / Samsung Trusted Apps?** — out of scope. Knox
  is enterprise-licensed. We do user-space heuristics; Samsung's
  layer happens whether we ship anything or not.

---

## #9 mdm-local — sovereign on-device policy advisor

**Status:** planned. Lowest priority; smallest user base; hardest
honest framing.

### Pitch
Like a corporate MDM in shape, but you're the admin. Define posture
policies (e.g. "no unknown-source installs", "biometric must be
enrolled", "screen-lock timeout ≤ 30s", "VPN must be active"). The
app continuously checks the device against these policies and
**surfaces violations** — it does NOT enforce. (We can't enforce
without Device Owner privileges, which require factory-reset
provisioning and aren't reachable rootless.)

Honest framing: an MDM-shaped *advisor*, not enforcer.

### Threat model
- Policy drift over time — user lowers screen-lock timeout, disables
  biometric, installs from unknown source, etc.
- Multi-device fleet (e.g. you have 3 phones) — keep posture
  consistent across all.
- Posture-snapshot bookkeeping — be able to attest "as of T, my phone
  was in compliance with X, Y, Z policies."

### Permissions
- `QUERY_ALL_PACKAGES` (overlap with antivirus — could be one app,
  or two with shared library). Probably mdm-local doesn't need it
  if antivirus is also installed.
- `POST_NOTIFICATIONS` — violation alerts.
- Otherwise comms-stripped.

### Core UI flows
1. **Dashboard** → green-yellow-red posture indicator. Count of
   policies satisfied / violated.
2. **Policies** → toggle each policy on/off, set thresholds.
3. **Violation log** → timestamp, policy, deviation, remediation
   suggestion.
4. **Attestation export** → produce a signed JSON snapshot of current
   posture for an external party (employer, auditor).

### Cross-app interactions
- **antivirus** — they share signal: antivirus flags an app, mdm-local
  records a violation.
- **firewall** — mdm-local policies can include "VPN must be active"
  → checks firewall app's status.
- **passgen** / **aegis** — policies like "vault must be set up",
  "OTP secrets must exist for these accounts."

### Distribution constraints
- Play Store: probably fine. Not user-impacting.
- F-Droid: easy.

### Open questions
- **Combine with antivirus into one app?** They overlap a lot in
  permission needs and threat model. Combined "device posture" app
  is cleaner. Probably yes.
- **Signed attestation format** — JWT? Custom? Use ML-DSA for
  post-quantum signatures? Yes — this is one place ML-DSA actually
  fits, since attestation is a public-key signature use case.

---

## Suite-wide design notes

### Permission summary (final form)

The whole suite's user-visible permissions, by app:

| App | User-visible permissions |
|---|---|
| passgen | USE_BIOMETRIC |
| aegis | USE_BIOMETRIC |
| firewall | INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS |
| backups | POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED |
| browser | INTERNET, ACCESS_NETWORK_STATE, READ_MEDIA_*, POST_NOTIFICATIONS |
| vault-folder | USE_BIOMETRIC, POST_NOTIFICATIONS |
| messenger | READ_SMS, RECEIVE_SMS, SEND_SMS (opt-in), READ_PHONE_NUMBERS, POST_NOTIFICATIONS |
| antivirus | QUERY_ALL_PACKAGES, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS |
| mdm-local | QUERY_ALL_PACKAGES, POST_NOTIFICATIONS |

**No GMS, no Google Play Services, no Firebase, no AdMob, no analytics
SDKs anywhere in the suite. Every permission justified by a specific
feature.**

### Cross-app IPC contract

Suite apps that need to talk to each other use:
- `<queries>` block declarations for visibility (no
  QUERY_ALL_PACKAGES).
- ContentProvider with signature-level permission
  `com.understory.suite.SUITE_INTERNAL` for backup-export and
  attestation-status queries. Only siblings signed by the same
  keystore can bind.

### Build-time invariants

- Every app's `verifyCertPin` runs against every release APK.
- `SuiteAttestation` runs at every app's launch.
- Lint config (android/lint.xml) shared across all modules.
- ProGuard rules shared via `proguard-rules-suite.pro` (still TBD).

### Distribution

F-Droid is primary. Direct sideload from project's GitHub releases is
the secondary distribution. Play Store would require per-app policy
review work and we're not committing to that until v1.0.

### Maintenance commitment

Each app needs ~quarterly Android-API-tracking attention. The suite's
viability depends on continued tending. If a contributor walks away
for a year, the suite degrades — not because it's bad architecture
but because Android moves under it.

### What's definitely *not* in the suite

- No "social" apps (Twitter/Facebook clients, etc.)
- No payment apps
- No camera / photo-editor apps
- No music / video players
- No games

The suite is bounded to "user privacy and security infrastructure."
That's the brand. Everything else gets recommended elsewhere.
