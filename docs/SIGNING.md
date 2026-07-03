# Release signing

How suite APKs get signed, where the keys live, which cert pins gate which
builds, and what a rotation costs. Companion to `keystore/README.md` (the
committed **debug** keystore) and `RELEASE_BLOCKERS.md`.

## Keystore location doctrine

The release keystore is **offline material**. It exists in exactly two places:

1. The operator's secrets directory on the build box:
   `%USERPROFILE%\secrets\understory-release.jks` with its passphrase in
   `understory-release-keystore.pass` next to it.
2. An offline USB copy of both files (disaster recovery — losing this
   keystore permanently strands every installed user, since Android refuses
   cert-mismatched updates).

Format: PKCS12. Key alias: `understory`. Key password = store password
(single passphrase, held only in the `.pass` file).

**Never** commit the keystore or the passphrase file to any repo, paste
either into chat, or upload them as CI secrets. CI builds debug only; release
signing happens on a box that already holds the keystore, wired in
per-invocation via gradle properties (below). Nothing in the repos ever
contains key material — only the *cert digest*, which is public by design.

## The two pins

Single source: `common-security/src/main/java/com/understory/security/SuitePins.kt`.
The digest pair is written once there; `Tamper`, `SuiteAttestation`, and
`SuiteCapabilityRegistry` all read `SuitePins.EXPECTED_CERT_SHA256`, which
selects by build variant (`BuildConfig.DEBUG` of `:common-security`).

| Pin | Digest (SHA-256, lowercase hex) | Gates |
| --- | --- | --- |
| `DEBUG_CERT_SHA256` | `aba68a81a0d63b5549794e586875a4f04e6dba3a6fe25d363e04eb75f46df69e` | Debug builds. Committed suite `keystore/debug.keystore`. |
| `RELEASE_CERT_SHA256` | `59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a` | Release builds. Offline `understory-release.jks`. |

Enforcement is two-layered:

- **Runtime:** each app hard-fails if its own cert doesn't match the active
  pin (`Tamper`), and refuses to run if an installed sibling's cert doesn't
  match it (`SuiteAttestation`); unverified peers contribute zero
  capabilities (`SuiteCapabilityRegistry`).
- **Build time:** the root `verifyCertPin` task (every app repo) runs after
  every `assemble*`, extracts the APK's signer digest with `apksigner`, and
  compares it against the variant's pin from `SuitePins.kt` — debug APKs
  against the debug pin, release APKs against the release pin. Mismatch is a
  hard build failure. Unsigned release APKs are refused when
  `-PrequireSignedRelease=true` is set (CI's `release-check` job and every
  publication build must set it).

## Release build command

From each app repo root, on the box holding the keystore:

```
gradle assembleRelease verifyCertPin \
  -PrequireSignedRelease=true \
  -PreleaseKeystore=%USERPROFILE%\secrets\understory-release.jks \
  -PreleaseKeystorePassFile=%USERPROFILE%\secrets\understory-release-keystore.pass
```

When the two `-Prelease*` properties are absent, the release build type has
no signingConfig and the APK comes out unsigned, exactly as before — that is
the CI default, and `verifyCertPin` warns (or fails, under
`-PrequireSignedRelease=true`) rather than silently passing.

The `release-check` GitHub workflow job (workflow_dispatch only) runs the
same command *without* the keystore properties, so on CI it validates the
unsigned-refusal path; a red run there is the gate working.

## Rotation procedure

Rotating the release keystore (compromise, algorithm sunset, loss):

1. Generate the new keystore **offline** (PKCS12, alias `understory`, single
   passphrase) and place it + its `.pass` file in the secrets dir and on the
   USB copy. Read the new cert SHA-256 (lowercase, no colons):
   ```
   keytool -list -v -keystore understory-release.jks -storetype PKCS12 \
     | grep SHA256 | tr -d ': ' | tr '[:upper:]' '[:lower:]'
   ```
2. Update `RELEASE_CERT_SHA256` in `SuitePins.kt` (understory-common is the
   canonical home; let the sync script propagate the vendored copies).
3. Rebuild **all seven apps** with the release command above. `verifyCertPin`
   is the drift check: it fails any APK whose signer doesn't match the new
   pin.
4. Ship all seven together. This is not optional: `SuiteAttestation` makes
   siblings cross-check each other's certs, so a device holding one new-cert
   app and one old-cert app has every suite app refusing to run until all
   are updated. There is no mixed-cert grace mode — that asymmetry is the
   defense.
5. Users must uninstall/reinstall (Android refuses cert-mismatched updates).
   Publish the rotation notice through the hash-manifest channel
   (`RELEASE_BLOCKERS.md`), not just the download page.

Debug keystore rotation is the same shape but touches
`DEBUG_CERT_SHA256` — see `keystore/README.md`.
