# Release-readiness checkpoint

## Identity

- Repository: `Zheke32174/understory-common`
- Checkpoint branch: `security/public-signing-containment-v1`
- Reviewed default head: `ba4eb20e7fe4972d5263659397885d0cef64e3c6`
- Coordination issue: #3

## Last completed scope

Public signing identity, shared trust primitives, release automation assumptions, security reporting, licensing presence, and public/private presentation.

## Resolved on this draft

- Removed the publicly exposed shared debug private key from the current tree.
- Revoked debug signing as authorship, sibling-attestation, and capability authority.
- Preserved local debug buildability without a suite-wide key.
- Retained only the external release certificate as authenticated suite identity.
- Added incident provenance, security guidance, key ignore rules, and deterministic validation.
- Corrected private-repository and nonexistent-public-component claims.

## Open blockers

- The key remains reachable in public history and existing artifacts; history rewriting is not authorized.
- Existing debug APK releases/tags require an explicit steward disposition.
- The source tree has no explicit license; no license was invented.
- App repositories must integrate coordinated vendored-code and workflow changes.
- Offline release-key custody has not been independently attested.
- Branch rules, private vulnerability reporting, secret scanning, push protection, and immutable-release settings require administrative verification.
- No signed release candidate or consumer-verification receipt exists.

## Validation receipts

Pending exact-head hosted validation. Static signing-boundary checks are part of both ordinary workflows.

## Reconsideration triggers

New commit, changed CI result, newly discovered key material, changed release artifact, changed visibility or public claim, license decision, signing rotation, or explicit steward request.

## Next action

Validate this branch and apply the same boundary to every vendored app repository before any integration decision.
