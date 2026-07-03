package com.understory.backup

import com.understory.security.Crypto
import java.io.InputStream
import java.io.OutputStream

/**
 * The shared export / import surface every vault app exposes (design §5).
 *
 * The at-rest format is ALWAYS the encrypted [BackupEnvelope] +
 * [AesGcmPassphraseCodec] (design §3.2) — no new codec, no new KDF, no fork of
 * the envelope. This object is a thin, honest wrapper that:
 *
 *   - takes an app's [BackupAdapter] plaintext payload and a
 *     recovery key, and produces / consumes the `.usbe` file, and
 *   - centralises the passphrase-material lifecycle (wipe-in-finally) so each
 *     of the four apps doesn't re-hand-roll the try/finally and leak the key.
 *
 * The passphrase is the vault's **recovery key** (`common-security`
 * `VaultRecovery.RecoveryKey`), NOT the raw hardware KEK — so the exported file
 * survives re-enrollment and restores on a new device with only the key the
 * user holds (design §5, step 4). New exports therefore carry adapter cleartext
 * under a distinct recovery key; backups' legacy "recovery-key == KEK over an
 * envelope" files remain readable via the [SchemaVersions] split below.
 *
 * common-security is already a dependency of common-backup
 * ([AesGcmPassphraseCodec] uses [Crypto]); this file adds no new coordinates.
 */
object VaultRecoveryEnvelope {

    /** Default export filename stem. Callers append the app slug + date. */
    const val FILE_EXTENSION: String = "usbe"

    /**
     * Schema-version conventions for the cleartext payload carried inside the
     * envelope. The ENVELOPE format version is separate
     * ([BackupEnvelope.CURRENT_FORMAT_VERSION]) and unchanged; this is the
     * `header.schemaVersion` field the adapter branches on at import.
     *
     * The split lets backups keep its pre-v2 recovery-key model working: a file
     * whose [BackupEnvelope.Header.schemaVersion] is < [SPLIT_KEY_MIN] was
     * written under the legacy convention (recovery key == raw KEK, payload is
     * the vault blob) and is routed to the app's legacy import branch. New
     * exports use >= [SPLIT_KEY_MIN] (distinct recovery key, payload is adapter
     * cleartext). Old `.usbe` files stay parseable because this is a
     * payload-schema concern, not an envelope-format change (design §3.4).
     */
    object SchemaVersions {
        /** Any schemaVersion below this is a legacy (recovery-key == KEK) file. */
        const val SPLIT_KEY_MIN: Int = 2

        /** True if [schemaVersion] should take the app's legacy import branch. */
        fun isLegacy(schemaVersion: Int): Boolean = schemaVersion < SPLIT_KEY_MIN
    }

    /**
     * Codec public params recorded in the envelope header so a reader can see
     * (non-secret) which KDF produced the file, matching the suite constants in
     * [Crypto]. Purely informational — the codec re-derives from the on-disk
     * salt regardless.
     */
    fun codecParams(): Map<String, String> = mapOf(
        "kdf" to "argon2id",
        "mem_kib" to Crypto.ARGON2_MEMORY_KIB.toString(),
        "iters" to Crypto.ARGON2_ITERATIONS.toString(),
        "par" to Crypto.ARGON2_PARALLELISM.toString(),
    )

    /**
     * Write an encrypted recovery export to [out].
     *
     * @param out          destination (typically a SAF `CreateDocument` stream).
     * @param appId        the adapter's [BackupAdapter.appId].
     * @param schemaVersion the adapter's [BackupAdapter.schemaVersion]
     *                      (must be >= [SchemaVersions.SPLIT_KEY_MIN] for a
     *                      new split-key export).
     * @param plaintext    the adapter's [BackupAdapter.export] bytes. This
     *                      method wipes [plaintext] in a finally — the caller
     *                      hands ownership over.
     * @param recoveryKeyChars the vault recovery key characters. Ownership is
     *                      NOT taken; the caller wipes them (they typically
     *                      belong to a `VaultRecovery.RecoveryKey`). A private
     *                      copy is made and wiped internally so a slow caller
     *                      can't leave the codec holding the key.
     * @param label        optional user label stored in the header.
     * @param nowMs        wallclock millis for the header timestamp.
     */
    fun writeEncrypted(
        out: OutputStream,
        appId: String,
        schemaVersion: Int,
        plaintext: ByteArray,
        recoveryKeyChars: CharArray,
        label: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val header = BackupEnvelope.Header(
            appId = appId,
            schemaVersion = schemaVersion,
            createdAtMs = nowMs,
            label = label,
            codecParams = codecParams(),
        )
        // Copy the key so the codec's PassphraseKey can own+wipe its own buffer
        // without disturbing the caller's RecoveryKey.
        val keyCopy = recoveryKeyChars.copyOf()
        val passphrase = AesGcmPassphraseCodec.PassphraseKey(keyCopy)
        try {
            BackupEnvelope.write(out, AesGcmPassphraseCodec, header, plaintext, passphrase)
        } finally {
            passphrase.wipe()
            Crypto.wipe(plaintext)
        }
    }

    /** The parse result plus a flag for whether the app's legacy branch applies. */
    data class Opened(
        val parsed: BackupEnvelope.Parsed,
        val isLegacy: Boolean,
    ) {
        val header: BackupEnvelope.Header get() = parsed.header
        val appId: String get() = parsed.header.appId
        val schemaVersion: Int get() = parsed.header.schemaVersion
    }

    /**
     * Parse (but do not decrypt) an incoming `.usbe` from [input]. Rejects
     * non-USBE files (magic check inside [BackupEnvelope.parse]). Lets the
     * import UI show a parsed summary and check [Opened.appId] against the
     * current app BEFORE prompting for the key (design §5.4, steps 2–3).
     */
    fun open(input: InputStream): Opened {
        val parsed = BackupEnvelope.parse(input)
        return Opened(parsed, SchemaVersions.isLegacy(parsed.header.schemaVersion))
    }

    /**
     * Decrypt an [Opened] envelope with [recoveryKeyChars]. On a wrong key or a
     * corrupt/tampered file the underlying GCM auth fails and this throws — the
     * import UI maps that to [com.understory.security.RecoveryCopy.IMPORT_WRONG_KEY].
     *
     * Ownership of [recoveryKeyChars] is NOT taken (caller wipes). The returned
     * plaintext is the adapter payload; the caller passes it to
     * [BackupAdapter.import] and then wipes it.
     */
    fun decrypt(opened: Opened, recoveryKeyChars: CharArray): ByteArray {
        val keyCopy = recoveryKeyChars.copyOf()
        val passphrase = AesGcmPassphraseCodec.PassphraseKey(keyCopy)
        try {
            return BackupEnvelope.decryptPayload(opened.parsed, AesGcmPassphraseCodec, passphrase)
        } finally {
            passphrase.wipe()
        }
    }
}
