package com.understory.backup

import com.understory.security.Crypto

/**
 * Default suite backup codec. Derive a 256-bit key from the user's
 * passphrase via Argon2id (suite-default parameters), then AES-256-GCM
 * encrypt with the envelope header bound as AAD.
 *
 * On-disk codec ciphertext layout (the bytes returned from [encrypt]
 * and consumed by [decrypt]):
 *
 *   +---------+--------------------------------------------+
 *   | offset  | field                                      |
 *   +---------+--------------------------------------------+
 *   |   0     | salt (32 bytes, fresh per encrypt)         |
 *   |  32     | iv (12 bytes, fresh per encrypt) +         |
 *   |         | aes-gcm ciphertext + 128-bit tag           |
 *   +---------+--------------------------------------------+
 *
 * The `iv || ct+tag` portion is exactly what [Crypto.aesGcmEncrypt]
 * returns, so we pass it through unchanged. The salt is prepended
 * because [BackupEnvelope] doesn't allow the codec to add fields to
 * the cleartext header (the header is built before the codec runs).
 *
 * Argon2id parameters are the suite compile-time constants
 * (memory=64 MiB, iterations=3, parallelism=1, output=32 bytes), which
 * matches every other passphrase-derivation point in the suite. A
 * future v2 codec may carry different params; downgrade is locked
 * behind the codec-id check at the envelope layer.
 *
 * Hygiene contract: [PassphraseKey] takes ownership of the supplied
 * CharArray. The caller MUST call [PassphraseKey.wipe] when finished
 * (or use the [BackupCodec.KeyMaterial.wipe] surface). The codec wipes
 * its own derived 32-byte key after each encrypt/decrypt regardless of
 * caller behavior, so a slow caller leaks at most the passphrase
 * itself, not the derived KEK.
 */
object AesGcmPassphraseCodec : BackupCodec {

    override val id: Int = BackupCodecIds.AES_GCM_PASSPHRASE
    override val name: String = "AES-256-GCM (passphrase)"

    /** Salt size matches Crypto.SALT_BYTES (32) for suite consistency. */
    private const val SALT_BYTES = Crypto.SALT_BYTES

    /**
     * Wraps the user passphrase. Takes ownership of [passphrase] —
     * caller must not retain or mutate the reference after construction.
     * [wipe] zeros the underlying CharArray.
     */
    class PassphraseKey(internal val passphrase: CharArray) : BackupCodec.KeyMaterial {
        override fun wipe() {
            Crypto.wipe(passphrase)
        }
    }

    override fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        key: BackupCodec.KeyMaterial,
    ): ByteArray {
        require(key is PassphraseKey) { "$name requires PassphraseKey, got ${key.javaClass.simpleName}" }

        val salt = Crypto.randomBytes(SALT_BYTES)
        val derivedKey = Crypto.argon2id(key.passphrase, salt)
        try {
            val ivAndCt = Crypto.aesGcmEncrypt(derivedKey, plaintext, aad)
            // salt || iv || ciphertext+tag
            val out = ByteArray(salt.size + ivAndCt.size)
            System.arraycopy(salt, 0, out, 0, salt.size)
            System.arraycopy(ivAndCt, 0, out, salt.size, ivAndCt.size)
            return out
        } finally {
            Crypto.wipe(derivedKey)
        }
    }

    override fun decrypt(
        ciphertext: ByteArray,
        aad: ByteArray,
        key: BackupCodec.KeyMaterial,
    ): ByteArray {
        require(key is PassphraseKey) { "$name requires PassphraseKey, got ${key.javaClass.simpleName}" }
        // Need at least salt + iv + 1 byte ct + 16 byte GCM tag.
        require(ciphertext.size > SALT_BYTES + 12 + 16) {
            "ciphertext too short: ${ciphertext.size} bytes"
        }

        val salt = ciphertext.copyOfRange(0, SALT_BYTES)
        val ivAndCt = ciphertext.copyOfRange(SALT_BYTES, ciphertext.size)
        val derivedKey = Crypto.argon2id(key.passphrase, salt)
        try {
            return Crypto.aesGcmDecrypt(derivedKey, ivAndCt, aad)
        } finally {
            Crypto.wipe(derivedKey)
        }
    }
}
