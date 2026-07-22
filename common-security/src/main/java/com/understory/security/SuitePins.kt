package com.understory.security

/**
 * Single source of truth for the Understory Suite release-signing identity.
 *
 * The former shared debug keystore was copied into public repositories. A key
 * whose private material is public cannot authenticate an APK, sibling, or
 * capability provider. Debug builds therefore remain development artifacts but
 * are never trusted as suite members.
 *
 * Release builds are trusted only when signed by the offline release key whose
 * certificate digest is pinned below. The private release key and passphrase
 * must remain outside GitHub, CI, chat transcripts, and build artifacts.
 */
object SuitePins {

    const val RELEASE_CERT_SHA256 =
        "59a3dee7feb8262170e4dcabb3dbe7bc323abe8715ab49f5bed5133046a45c4a"

    /** True only for release variants. Debug builds deliberately have no trust identity. */
    val TRUSTED_SUITE_IDENTITY_ENABLED: Boolean = !BuildConfig.DEBUG

    /** Certificate accepted for trusted release self-checks and peer checks. */
    const val EXPECTED_CERT_SHA256: String = RELEASE_CERT_SHA256
}
