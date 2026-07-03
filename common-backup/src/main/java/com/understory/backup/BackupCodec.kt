package com.understory.backup

/**
 * Pluggable encryption layer for [BackupEnvelope]. Three implementations
 * are planned:
 *   - [AesGcmPassphraseCodec] — Argon2id-derived key + AES-256-GCM (default).
 *   - PgpCodec — asymmetric, OpenPGP-format. Hardware-token friendly.
 *   - Luks2ContainerCodec — single-file LUKS2-compatible image so users
 *     can `cryptsetup luksOpen backup.img` from any Linux box.
 *
 * Codecs are composable at the application layer: e.g. wrap a PGP-encrypted
 * payload as a LUKS2 container by chaining `Luks2(Pgp(plaintext))`. The
 * envelope only records the *outermost* codec id; nested codecs are part
 * of the application-level payload semantics.
 */
interface BackupCodec {

    /** On-disk codec id stored in the envelope byte 5. */
    val id: Int

    /** Human label used in UI / errors. */
    val name: String

    /**
     * Material the codec needs at encrypt or decrypt time. Codec-specific.
     * E.g. [AesGcmPassphraseCodec.PassphraseKey] wraps a `CharArray`. Kept
     * as a sealed marker here so codec calls are type-checked and we can
     * `KeyMaterial.wipe()` from the orchestrator without knowing the
     * concrete type.
     */
    interface KeyMaterial {
        /** Best-effort zero-out of any in-memory secret. */
        fun wipe()
    }

    /**
     * @param plaintext  payload to encrypt, e.g. the JSON of an aegis vault
     * @param aad        envelope header bytes; integrity-bound to the result
     * @param key        codec-specific [KeyMaterial]
     * @return opaque ciphertext (codec-specific layout)
     */
    fun encrypt(plaintext: ByteArray, aad: ByteArray, key: KeyMaterial): ByteArray

    /**
     * @param ciphertext output of a previous [encrypt]
     * @param aad        envelope header bytes (must match what was used at encrypt)
     * @param key        codec-specific [KeyMaterial]
     * @return plaintext, or throws on auth failure / corruption
     */
    fun decrypt(ciphertext: ByteArray, aad: ByteArray, key: KeyMaterial): ByteArray
}

/** Stable codec ids. New codecs MUST register here, never reuse. */
object BackupCodecIds {
    const val AES_GCM_PASSPHRASE = 1
    const val PGP = 2
    const val LUKS2_CONTAINER = 3
}
