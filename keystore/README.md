# Debug signing identity revoked

The former shared `debug.keystore` was committed while this code lived in a private repository. The suite was later split into public repositories without revisiting that assumption. The private key, default passwords, and certificate pin therefore became public.

That certificate digest was:

```text
aba68a81a0d63b5549794e586875a4f04e6dba3a6fe25d363e04eb75f46df69e
```

It is **revoked as an authentication or attestation identity**. Anyone can produce an APK carrying that certificate, so it cannot establish authorship, integrity, sibling identity, or capability authority.

## Current rule

- No shared debug private key belongs in a public repository.
- Developers use their normal local Android debug key for local builds.
- Debug APKs are untrusted development artifacts and must not be published as releases or advertised as suite-authenticated downloads.
- Only APKs signed by the separately held offline release key may satisfy `Tamper`, `SuiteAttestation`, or `SuiteCapabilityRegistry` identity checks.
- The release private key and passphrase remain outside GitHub and CI.

## Migration consequence

Previously installed APKs signed with the revoked debug key must not be treated as trusted. Android will not update them in place to an APK signed by a different key; users must uninstall the old debug build and install a verified release-signed build when one exists.

The old blob remains reachable in Git history until an explicitly authorized history-rewrite operation is performed. Deleting it from the current tree prevents future accidental use but does not make the historical key secret again.
