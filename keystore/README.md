# Signing material boundary

No signing private key belongs in this public repository.

Android generates a developer-local debug keystore when needed. Debug certificates differ across developer machines and are explicitly **not** Understory trust roots.

The former suite-wide debug keystore was removed after the repositories became public. Its certificate and private key must be treated as public and revoked for authorship, tamper, sibling-attestation, and capability decisions. Prior debug APKs signed with that identity remain development artifacts only.

Trusted distribution requires the separately held offline release key described in `docs/SIGNING.md`. Only its public certificate digest is stored in source.
