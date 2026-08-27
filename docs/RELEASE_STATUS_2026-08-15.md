# Understory release status — 2026-08-15

This is a **status ledger**, not a replacement for `RELEASE_BLOCKERS_V2.md`.
The blocker document remains the release doctrine. This file records which
requirements are already implemented in current source, which have current CI
evidence, and which still require device/reproducibility evidence before a v1
release can be called stable.

Status vocabulary:

- **SOURCE-GREEN** — the required implementation is present in current source.
- **CI-GREEN** — a current or identified candidate commit assembled/tested in GitHub Actions.
- **DEVICE-PENDING** — correctness depends on real Android behavior and still needs an on-device receipt.
- **REPRO-PENDING** — reproducibility/distribution proof remains outstanding.
- **OPEN** — implementation/evidence is genuinely absent or incomplete.

Do not promote SOURCE-GREEN to DEVICE-GREEN by inference.

## Scope

Canonical Understory security suite (`understory-common` source of truth):

1. `understory-passgen`
2. `understory-aegis` — store face: **Understory OTP**
3. `understory-firewall` — store face: **Godwall**
4. `understory-vault-folder`
5. `understory-antivirus`
6. `understory-backups`
7. `understory-browser`

Related/satellite Android projects currently being reconciled separately:

- **Chaos Orb** — separate app under `understory-firewall/emi-chaos-bench/`
- **Yojimbo** — `understory-yojimbo`
- **Genji** — `understory-genji`
- **Masamune** — `Masamune`

## V2 non-negotiables reconciled against current source

### N1 — vault invalidation / recovery

**SOURCE-GREEN; DEVICE-PENDING.**

The shared vault recovery contract now contains:

- `KeyPermanentlyInvalidatedException` classification;
- startup key-state detection;
- user-held recovery-key generation and verification;
- mandatory recovery enrollment bookkeeping;
- recovery-export refresh prompting;
- export-first/reset state machine.

App-specific integration is present in all four vault-bearing apps:

- Passgen: `VaultActivity` recovery routing;
- Understory OTP: `MainActivity`, `AegisResetHooks`, recovery screens/adapters;
- Vault Folder: recovery state store/screen/reset hooks/export adapter;
- Backups: `MainActivity` recovery integration.

**Still required:** real-device receipts for biometric/screen-lock re-enrollment,
permanent-key invalidation detection, reset, and restore on the target Samsung
and at least one AOSP/Pixel-class device.

### N2 — no secret roach motel

**SOURCE-GREEN; DEVICE-PENDING.**

Current source contains the required import/export paths:

- Passgen: dedicated import/export format implementations including Bitwarden-compatible flows;
- Understory OTP: file imports plus Aegis-compatible/plaintext/encrypted export flows;
- Vault Folder: SAF `CreateDocument` export, recovery-envelope export, and the stale-object-safe export-ID flow;
- Backups: `BackupEnvelope` encrypt/decrypt and restore paths.

**Still required:** end-to-end round-trip tests on device using representative
real exports, including wrong-key/corruption failures.

### N3 — no false-green security output

**SOURCE-GREEN; DEVICE-PENDING.**

- Understory OTP: `AegisCode` honors SHA1/SHA256/SHA512, digits, period, and HOTP counter; HOTP advancement is persisted before a code is returned.
- Antivirus: `EnabledAbusers` enumerates currently enabled accessibility services, active device admins, and enabled notification listeners; `PlayProtectStatus` returns `UNKNOWN` when the modern-device state is absent/unreadable rather than defaulting green.
- Godwall: `TunnelPosture` is tri-state and does not emit the strongest/green verdict through an unreadable/inference-gap state.

**Still required:** on-device fixtures that exercise the Samsung/Android settings
paths and prove the UI renders UNKNOWN/active-abuser states correctly.

### N4 — Vault Folder crash + Backups restore

**SOURCE-GREEN; DEVICE-PENDING.**

- Vault Folder no longer stores a non-Parcelable entry object through `rememberSaveable`; it stores the entry ID string, re-resolves live metadata after SAF returns, and writes via `CreateDocument`.
- Backups has authenticated streaming `decrypt(InputStream -> OutputStream)` for `USTRSTRM` content plus a dedicated `UserDirsContentRestore` path and stream-codec tests.

**Still required:** actual SAF round-trip and large streaming restore on device.

### N5 — honesty pass

**MOSTLY SOURCE-GREEN; UI SWEEP PENDING.**

Already corrected in current source:

- old overclaim vocabulary is gone from live capability code (`NETWORK_FILTER`, `REALTIME_SCANNER`, `BACKUP_ORCHESTRATOR` are not live enum capabilities);
- narrower shipped names are used (`NET_POSTURE_AUDIT`, `APK_AUDITOR`, `BACKUP_ENVELOPE`);
- Browser v1 is intentionally capability-empty in `KNOWN_PEERS` until a peer-facing surface exists;
- Aegis store-face collision is resolved: app name is **Understory OTP**;
- copy text explicitly treats clipboard clearing as best-effort rather than guaranteed.

Found during this reconciliation:

- `Snapshot.unknownVersionPeers` incorrectly treated any capability-empty peer as
  an unknown version. That falsely labels recognized Browser v1 as version-unknown.
- Fix is on `understory-common` branch `release/reconcile-2026-08-15`:
  `PeerInfo.versionRecognized` now distinguishes a recognized inert version from
  a genuinely unknown version; JVM regression tests were added.

**Still required:** full device UI sweep for dead controls, stale copy, status
colors, process-death cleanup claims, and any remaining doc/comment drift.

### N6 — VPN/scarce-slot doctrine

**SOURCE-GREEN; DEVICE-PENDING.**

Godwall current source defines:

- `COMPANION` as default observe/advise mode that never prepares/starts `VpnService`;
- `STANDALONE` as explicit opt-in/default-off;
- `VpnSlotProbe` as a fail-closed incumbent-VPN veto, including Tailscale-safe behavior.

**Still required:** real-device proof that an active Tailscale tunnel is never
evicted and that Standalone refuses correctly under handover/split-tunnel cases.

### N7 — eng/prod provider-authority collision

**SOURCE-GREEN.**

Canonical app manifests use `${applicationId}.suitecaps`, including Passgen,
Backups, Browser, and the other indexed suite manifests. Eng/prod application IDs
therefore receive distinct provider authorities.

**Still required:** install both variants together on device as a release test receipt.

## Current CI baseline evidence

These are **build evidence**, not release promotion by themselves.

| Project | Candidate commit | Workflow evidence | Artifact digest |
|---|---|---|---|
| Passgen | `1e37f74fc971a8dfc3bfe86d36b1cc5c81d6e31b` | main Android workflow success, run `29696802705` | `sha256:5bf1c67a236b3b9d96bb3885601d8d8f971650cae9648b93b9745a5b10526014` |
| Understory OTP | `f15ff0be58a7b61a29027b0b7ca97be1505d0e48` | main Android workflow success, run `29696142059` | `sha256:56408d2bd6cbc302b18a94606d8810666db47db8e176a4b56243b143011b23f5` |
| Godwall + Chaos Orb | `cb022941861022d2fb9d7601b8eadd004ea5c296` | main Android workflow success, run `30727817353` | `sha256:c6dd8ef8d7804382bbd96bf3f10a6aa0f1d881a75d902337d39b37eeb48cbb36` |
| Vault Folder | `0b9cb9f1795b8d36b1c54bcb90bfd8736df4694a` | main Android workflow success, run `29696873407` | `sha256:1ceda47c971d854c98a89a5381963647a8fbb726e43c147e87256c1dd32700be` |
| Antivirus | `7acc8f69f79cdfdad74457f84497bafa294614ea` | main Android workflow success, run `29696747834` | `sha256:54cca8fb91b52a8b2c75d9b728d6b0caa164491613b28cf541516cc12f6ae328` |
| Backups | `f84c26cdd97890eeb8041e7b8a21030b7576c2e1` | main Android workflow success, run `29696858932` | `sha256:9bd98243cc6d3839eeb30c686b1abeb8932ad959714f2bcee6c31f70397dc52b` |
| Browser | `63f86dc6be25d1b392a8f543b7ccedd9272a8794` | main Android workflow success, run `29696862625` | `sha256:d5cc7142dfc35a312bead7d3cffc0292c85bc4fd4307bc462862a7912f8c7863` |
| Yojimbo candidate | `826dd5a23ae41fa4567dbcf5cb3e4d3d5ffcaf1d` | PR #13 build/unit-test success, run `30933930712` | `sha256:d4900ae397d94b6559defb9829360570ab1b34392b67e60dd9721def84831b39` |
| Genji candidate | `b6c88bfc6778b676a74b74aa3744fdc1da5329b3` | SDK setup + assemble + unit tests + artifact upload success, run `31881685211` | `sha256:67a8c2ce7414ffb70e2e2f5f0891ba41714086996c88a6934c88321c46c3b43b` |
| Masamune candidate | `d641a935c7bbaa6b152bcda04e2a37960b8bac5d` | current PR #3 verification run `31881996989` | **in progress when this ledger was written** |

### CI infrastructure repair made during reconciliation

Genji's previous Android release failure was not an app compile failure. The
shared reusable workflow used `yes | sdkmanager ...` under `pipefail`; when
`sdkmanager` exited successfully, `yes` received SIGPIPE and the step returned
failure. Pleiades PR #68 corrected both SDK-install steps to return
`PIPESTATUS[1]` (the `sdkmanager` status). The fix was merged as
`eedcc22c04b0bf06a4b659db3367ed53433c4e71`, Genji was repinned to it, and the
fresh run above passed setup, assembly, unit tests, and artifact upload.

## Signing observations

Baseline APK inspection found the canonical suite debug APKs using the shared
suite debug certificate as expected. **Chaos Orb is different:** its sibling
Gradle build has no suite debug-signing configuration and its green CI APK uses a
different ordinary debug certificate.

Do **not** change Chaos Orb's signer until the installed phone copy is queried.
Changing signer first could destroy update-in-place compatibility. Required next
receipt: installed-package signer + package/version + APK hash from the phone,
then decide whether to preserve that lineage or deliberately migrate it.

## Carried-v1 blockers — current status

### Dependency lockfiles + committed Gradle wrapper

**OPEN.**

At least Passgen current `main` has neither committed `gradlew`/wrapper files nor
Gradle dependency-lock configuration/lockfiles in the root tree. Code search
across the canonical app set also found no dependency-lock configuration.

This must be audited and implemented coherently across all seven app repos before
claiming reproducible v1 release inputs.

### Independent byte-identical rebuild

**REPRO-PENDING.**

A recipe and CI build evidence are not an independent byte-identical rebuild.
Need a clean second environment to rebuild an exact release candidate and compare
bytes/digests.

### Separate offline-signed SHA-256 publication channel

**REPRO-PENDING.**

CI artifacts carry SHA-256 digests, but the blocker specifically requires an
out-of-band/offline-signed manifest separate from the GitHub raw source channel.
That receipt has not been established by this reconciliation.

### Samsung One UI full retest

**DEVICE-PENDING.**

Must be executed against the final candidate set with release/testing flags in
the intended state. The current chat cannot claim this because the desktop/phone
control path is unavailable.

### Pixel/AOSP test

**DEVICE-PENDING.**

No current receipt established here.

### User-facing README/install/trust-assumption pass

**OPEN / PARTIAL.**

Several repos contain user-facing README/download material, but this ledger has
not yet verified the required plain-language trust assumptions and non-developer
install path across all seven apps.

## Phone / desktop source-of-truth intake status

A rotated phone MCP Quick Tunnel was confirmed externally live on 2026-08-15,
but this ChatGPT session lost the Tapzoid dynamic tool-dispatch namespace before
an authenticated phone inventory could be executed. The secondary remote-desktop
connector reports device `tapzoid` offline (last seen 2026-08-13).

Therefore **phone-vs-GitHub source/APK comparison remains DEVICE-PENDING**.
No bearer token or other transient secret is recorded in this repository.

Required intake once the control plane returns:

1. enumerate installed/source copies for the suite + Chaos Orb + Yojimbo + Genji;
2. record package, versionCode/versionName, signer certificate SHA-256, APK SHA-256;
3. hash/compare phone source trees against GitHub candidates;
4. preserve phone-only/newer files before any overwrite;
5. specifically resolve Chaos Orb signing lineage;
6. install/test eng+prod coexistence and the Samsung behavior matrix.

## Immediate release queue

1. Finish/verify `understory-common` registry honesty fix and propagate it with
   `tools/sync-common.sh` so every vendored app receives the same shared code and
   a sync receipt.
2. Complete Masamune PR #3 verification; do not merge solely because an older
   compile run was green.
3. Restore desktop/phone control and perform the hash/signer source-of-truth intake.
4. Implement committed Gradle wrapper + dependency locking across the canonical seven.
5. Run Samsung final device matrix and at least one Pixel/AOSP matrix.
6. Perform an independent byte-identical rebuild.
7. Produce and verify an offline-signed SHA-256 release manifest.
8. Only then cut/tag the stable v1 candidate set.
