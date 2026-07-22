# Release-readiness checkpoint

## Identity

- Repository: `Zheke32174/understory-common`
- Checkpoint branch: `security/public-signing-containment-v1`
- Reviewed default head: `ba4eb20e7fe4972d5263659397885d0cef64e3c6`
- Validated complete branch head: `c6bca21934978851f0f6f98ca0dc66e1a44ec95c`
- Coordination issue: #3

## Last completed scope

Public signing identity, shared trust primitives, release automation assumptions,
security reporting, licensing presence, public/private presentation, shared-code
compilation/tests, and propagation-contract validation.

## Resolved on this draft

- Removed the publicly exposed shared debug private key from the current tree.
- Revoked debug signing as authorship, sibling-attestation, and capability
  authority.
- Preserved local debug buildability without a suite-wide private key.
- Retained only the external release certificate as authenticated suite identity.
- Added incident provenance, security guidance, key ignore rules, and
  deterministic validation.
- Corrected private-repository and nonexistent-public-component claims.
- Replaced mutable workflow references and write authority with full-SHA-pinned,
  read-only validation on Ubuntu 24.04.
- Propagated the same source boundary to all six public app draft branches.

## Validation receipts

At exact complete branch head `c6bca21934978851f0f6f98ca0dc66e1a44ec95c`:

- Android shared-code validation run `29918731983` passed signing policy,
  Android compilation, and unit tests.
- Shared-code propagation run `29918731974` passed signing policy, host-command
  checks, shell parse/lint, and the complete propagation contract.

Earlier implementation head `b0ec1004a5e6b2ece27bea11c3903004002b4c11`
also passed runs `29917106551` and `29917106515` before the final read-only
workflow restoration.

## Changed conclusion

The common trust root and propagation boundary are green. The former public
debug identity remains compromised historically and must never again be treated
as an authenticated suite signer.

## Open blockers

- The key remains reachable in public history and existing artifacts; history
  rewriting is not authorized.
- Existing debug APK releases/tags require an explicit steward disposition.
- The source tree has no explicit license; no license was invented.
- Aegis, Passgen, and Antivirus still require exact unit-test repair receipts;
  Backups, Vault Folder, and Browser are already green.
- Offline release-key custody has not been independently attested.
- Branch rules, private vulnerability reporting, secret scanning, push
  protection, and immutable-release settings require administrative verification.
- No signed release candidate or consumer-verification receipt exists.

## Reconsideration triggers

New commit, changed CI result, newly discovered key material, changed release
artifact, changed visibility or public claim, license decision, signing rotation,
or explicit steward request.

## Next action

Repair the three exact app test failures, then decide the disposition of prior
debug releases and the future licensed, immutable, externally signed release
channel.
