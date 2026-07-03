package com.understory.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A NON-auth-bound Keystore wrap key for the self-sealing recovery kit
 * ([RecoveryFile]).
 *
 * This is the shared, suite-owned sibling of passgen's `ReceiptsCrypto`
 * (understory-passgen/.../ReceiptsCrypto.kt) — the SAME design, promoted into
 * common-backup so all four vault apps get one code path instead of cloning the
 * spec four times. It deliberately mirrors [com.understory.security.Crypto]'s
 * GCM parameters (AES-256-GCM, 128-bit tag) so the on-disk framing matches the
 * rest of the suite.
 *
 * Why a separate key, and why NOT auth-bound (the whole point):
 *
 *   - [com.understory.security.Crypto]'s device-auth key is built with
 *     `setUserAuthenticationRequired(true)` AND
 *     `setInvalidatedByBiometricEnrollment(true)` (Crypto.kt:155,165). That flag
 *     is a deliberate anti-coercion property: enrolling a new fingerprint or
 *     changing the lock screen makes the Keystore DESTROY that key, permanently
 *     bricking the vault's hardware-wrapped KEK.
 *   - This key sets NEITHER flag. It is device-bound (non-exportable Keystore
 *     material, StrongBox where available) but it SURVIVES the exact
 *     re-enrollment event that bricks the vault key. That survival is what lets
 *     [RecoveryFile] silently re-bind a vault after a fingerprint/lockscreen
 *     change with zero user action and nothing rendered on screen.
 *   - Because it is not auth-bound, `cipher.doFinal` needs no BiometricPrompt /
 *     CryptoObject — the seal/unseal paths run headlessly.
 *
 * The alias is versioned so a future spec change can migrate without colliding
 * with an existing key. Read-back of what this key protects is gated at the app
 * layer (the sealed kit only ever yields the KEK material to a same-device
 * re-bind path); this helper adds no policy of its own.
 */
object RecoveryWrapKey {

    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * Keystore alias for the recovery-kit wrap key. Suite-shared (not
     * per-app): each app runs in its own process/UID sandbox, so one alias
     * constant yields a distinct hardware key per app with no cross-app reach.
     */
    private const val WRAP_KEY_ALIAS = "understory_recovery_wrap_v1"

    private const val GCM_TAG_BITS = 128

    /** Encrypt-mode cipher under the wrap key. No prompt required. */
    fun cipherForEncrypt(): Cipher {
        val key = ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Decrypt-mode cipher for a previously stored [iv]. No prompt required. */
    fun cipherForDecrypt(iv: ByteArray): Cipher {
        val key = readKey() ?: error("recovery wrap key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /** True once the wrap key has been created on this device. */
    fun keyExists(): Boolean = readKey() != null

    /** Drop the wrap key (full app reset). The sealed kit becomes unreadable. */
    fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(WRAP_KEY_ALIAS)) ks.deleteEntry(WRAP_KEY_ALIAS)
        }
    }

    private fun readKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(WRAP_KEY_ALIAS)) return null
        return (ks.getEntry(WRAP_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun ensureKey(): SecretKey {
        readKey()?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // Same builder shape as Crypto.ensureDeviceAuthKey() but WITHOUT
        // setUserAuthenticationRequired / setUserAuthenticationParameters /
        // setInvalidatedByBiometricEnrollment. Not auth-bound: writable/readable
        // headlessly, and survives biometric re-enrollment.
        val spec = KeyGenParameterSpec.Builder(
            WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { runCatching { setIsStrongBoxBacked(true) } }
            .build()
        kg.init(spec)
        return kg.generateKey()
    }
}
