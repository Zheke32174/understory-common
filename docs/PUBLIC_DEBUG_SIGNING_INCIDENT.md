# Public debug-signing identity incident

**Recorded:** 2026-07-22  
**Coordination issue:** `understory-common#3`

## Finding

The Android suite was split from a private repository into public repositories without removing a shared debug keystore. The same private key was vendored across the shared repository and six app repositories. Its standard debug credentials were documented, and its certificate digest was used by runtime self-checks, sibling attestation, and capability discovery.

## Security consequence

The key can no longer prove that an APK was produced by the suite steward. Anyone can sign an APK with the former debug identity. Prior public debug APKs are untrusted development artifacts; the former debug certificate is revoked for tamper decisions, sibling identity, and capability authority.

No evidence was found in the reviewed public tree that the offline release private key or passphrase was committed. That conclusion is evidence-bound and must be reconsidered if repository history, artifacts, or external storage produce contrary evidence.

## Work outside source control

Existing GitHub Release assets and movable tags were not changed by this draft-only pass. Steward review must decide whether to delete, relabel, or retain them as incident evidence. Installed debug APKs should be treated as development installs and replaced only through an explicitly reviewed migration.
