# Release-readiness checkpoint

- Repository: `Zheke32174/understory-common`
- Reviewed head: `ba4eb20e7fe4972d5263659397885d0cef64e3c6`
- Draft: `security/revoke-public-debug-identity-v1`
- Status: **HOLD — shared public debug signing identity revoked; coordinated app containment required**

## Completed scope

Reviewed suite signing, certificate pins, vendored key material, release workflows, public download claims, and CI authority.

## Changed conclusion

The suite was split from a private source into public repositories without revisiting the shared debug-signing design. The debug private key became public while its certificate was still treated as an authenticity and cross-app capability boundary. That identity cannot authenticate suite software.

## Resolved on this draft

- Removed the shared debug private key from the current tree.
- Made the separately held release certificate the sole trusted suite identity.
- Replaced the obsolete private-repository rationale with a revocation and migration notice.

## Open blockers

1. Propagate the trust-boundary repair to every vendored app repository.
2. Remove shared debug key material from each current tree.
3. Stop automatic publication of debug APKs as normal/latest releases.
4. Replace write-capable release-on-push workflows with read-only validation.
5. Correct public download and install claims.
6. Obtain explicit authorization before changing existing releases, tags, or reachable history.
7. Produce release-signed self-check, sibling-attestation, capability-denial, install, update, rollback, and removal receipts.
8. Verify repository security and immutable-release settings administratively.

## Evidence reuse

The same key blob and pin were confirmed across the canonical shared repository and six public app repositories. Existing evidence should be reused rather than regenerated unless a branch, release, signing path, or key status changes.

## Reconsideration triggers

Reprocess on an affected branch or workflow change, new evidence concerning the offline release key, release cleanup, a release-signed fixture, verified administrative settings, or an explicit steward request.

## Next action

Create coordinated draft containment changes in the app repositories that publish or distribute debug APKs.

No default branch, release, tag, repository setting, installed app, or live system was changed.
