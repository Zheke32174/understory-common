# understory-common

Canonical home of the **Understory Suite** shared code and suite-level docs.

The Understory Suite is a coordinated set of rootless, in-bounds, local-first
Android security apps: [passgen](https://github.com/Zheke32174/understory-passgen),
[aegis](https://github.com/Zheke32174/understory-aegis),
[firewall](https://github.com/Zheke32174/understory-firewall),
[vault-folder](https://github.com/Zheke32174/understory-vault-folder),
[antivirus](https://github.com/Zheke32174/understory-antivirus),
[backups](https://github.com/Zheke32174/understory-backups),
[browser](https://github.com/Zheke32174/understory-browser).

## Contents

- `common-security/` — Tamper (signature pin + hook detection), SuiteAttestation
  (cross-app cert mesh), Totp/Hotp, SecureButton (tap-jack filtering), A11yProbe,
  DeviceProfile, Diagnostics, TestingMode, Crypto.
- `common-backup/` — encrypted backup envelope + streaming AES-GCM codecs.
- `overlay-i2p/`, `overlay-lokinet/`, `overlay-yggdrasil/` — overlay-network
  providers used by firewall + browser.
- `keystore/` — pinned suite **debug** keystore (intentionally committed; see its
  README). Its cert digest IS the suite pin.
- `docs/` — SUITE_DESIGN, SUITE_ROADMAP, RELEASE_BLOCKERS (the definition of
  "done" for v1), REVIEW-NOTES, SAMSUNG_QUIRKS, OVERLAY_NETWORKS, sandbox notes.
- `tests/blackarch/` — the BlackArch defense matrix + per-threat runbooks.

## Model

Each app repo **vendors** the shared modules it needs (identical relative paths)
so every app builds self-contained with no submodule/token plumbing. This repo is
the single place shared code is *edited*; `tools/sync-common.sh` propagates to the
app repos:

```bash
tools/sync-common.sh ../understory-aegis ../understory-passgen ../understory-firewall \
  ../understory-vault-folder ../understory-antivirus ../understory-backups ../understory-browser
```

CI here runs the shared modules' unit tests on every push.

## Provenance

Split 2026-07-02 from `Zheke32174/underward` `android/` (commit `f867493`).
