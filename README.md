# understory-common

Canonical home of the **Understory Suite** shared Android code and suite-level security documentation.

> [!WARNING]
> The former suite-wide debug signing private key remained committed after these repositories became public. That key is now public and cannot establish authorship, tamper resistance, sibling identity, or capability authority. Debug APKs are untrusted development artifacts. See [`docs/PUBLIC_DEBUG_SIGNING_INCIDENT.md`](docs/PUBLIC_DEBUG_SIGNING_INCIDENT.md).

The public suite currently includes:

- [Understory OTP](https://github.com/Zheke32174/understory-aegis)
- [Passgen](https://github.com/Zheke32174/understory-passgen)
- [Vault Folder](https://github.com/Zheke32174/understory-vault-folder)
- [Antivirus](https://github.com/Zheke32174/understory-antivirus)
- [Backups](https://github.com/Zheke32174/understory-backups)
- [Browser](https://github.com/Zheke32174/understory-browser)

The firewall component is not advertised as public until a reviewed public repository and distribution boundary exist.

## Contents

- `common-security/` — tamper heuristics, release-signature verification, release-only sibling attestation, TOTP/HOTP, secure UI helpers, diagnostics, and cryptographic utilities.
- `common-backup/` — encrypted backup envelopes and streaming AES-GCM codecs.
- `overlay-i2p/`, `overlay-lokinet/`, `overlay-yggdrasil/` — optional overlay-network providers.
- `docs/` — suite design, threat model, release blockers, signing doctrine, reproducibility, audits, and incident records.
- `tests/blackarch/` — defensive test matrix and runbooks.

## Trust boundary

Local debug builds use each developer's ordinary Android debug identity. They are buildable and testable, but they are not authenticated suite distributions and cannot unlock trusted cross-app capabilities.

The only certificate recognized as the suite distribution identity is the offline release certificate recorded in `common-security/src/main/java/com/understory/security/SuitePins.kt`. Its private key must remain outside every repository and ordinary CI.

## Shared-code model

Each app vendors the shared modules it needs so it can build without submodule credentials. Shared code is edited here and propagated with `tools/sync-common.sh`.

CI validates shared code and verifies that no private signing key or debug trust pin re-enters the public tree. CI does not publish APK releases.

## Provenance

Split on 2026-07-02 from the private `Zheke32174/underward` Android tree at commit `f867493`. The public signing-boundary correction is tracked in issue #3.
