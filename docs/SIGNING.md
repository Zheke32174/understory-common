# Release signing

## Current trust model

The Understory repositories are public. No signing private key may be committed to them or supplied to ordinary GitHub Actions.

The former shared debug key is revoked as an identity mechanism because its private key is public. Android's normal per-developer debug signing remains appropriate for local build and test work, but debug APKs are not authenticated distributions, do not authenticate siblings, do not contribute trusted cross-app capabilities, and must not be published as normal or latest releases.

## Release identity

The public release certificate SHA-256 digest is stored in `common-security/src/main/java/com/understory/security/SuitePins.kt`.

The corresponding private key and passphrase are operator-controlled offline material. Source and ordinary CI may contain only the public certificate digest.

A trusted release build must be produced on an authorized signing host with:

```text
gradle assembleRelease verifyCertPin \
  -PrequireSignedRelease=true \
  -PreleaseKeystore=<external-path> \
  -PreleaseKeystorePassFile=<external-path>
```

The build must fail if the APK is unsigned or its current signer does not match the recorded release certificate.

## Publication boundary

No default-branch or pull-request workflow may publish, overwrite, or mark an APK release as latest. A future publication mechanism requires a separate reviewed design proving exact source identity, external release signing, signer verification, immutable versioned tags, overwrite refusal, checksums and provenance, consumer verification, rollback/removal guidance, and explicit steward authorization.

## Rotation

A release-key compromise or loss is a coordinated suite event. Generate the new key offline, update the public certificate digest, rebuild and verify every app, publish a migration notice, and account for Android signer continuity. Never silently accept a compromised signer through certificate history.
