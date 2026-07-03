package com.understory.backup

import android.content.Context
import com.understory.security.Crypto
import com.understory.security.RecoveryKeyCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Self-sealing, file-based vault recovery (operator directive 2026-07-03 —
 * "the screen is the enemy").
 *
 * A vault's recovery secret is NEVER rendered on screen (no text, no QR) and
 * NEVER typed by the user. Instead the app self-manages a random recovery key
 * `R` as an encrypted FILE:
 *
 *   1. SELF-SEAL — at vault-create, [seal] mints a random 32-byte `R` (the user
 *      is never prompted for it and never sees it), builds a self-contained
 *      recovery blob from `R` + the vault's KEK material, and writes it to
 *      app-private storage encrypted at rest by [RecoveryWrapKey] — a NON
 *      auth-bound Keystore key that SURVIVES biometric re-enrollment. So after a
 *      fingerprint / lock-screen change the app can SILENTLY re-bind the vault
 *      ([readKekFromSealedKit]) with zero user action and nothing on screen.
 *
 *   2. EXPORT — [exportKit] writes ONE opaque, self-contained recovery file to a
 *      user-chosen SAF location (USB, cloud). It carries `R` itself plus the
 *      `R`-encrypted KEK material, so it restores on a brand-new device. It is
 *      never displayed. Honest one-line copy for the app: "this file can restore
 *      your vault — keep it somewhere safe; anyone who has it can open it."
 *
 *   3. RESTORE — [importKit] reads an exported file via SAF (OpenDocument) and
 *      returns the KEK material. The user never types a key. On a
 *      re-enrollment-bricked vault the app tries [readKekFromSealedKit] first
 *      (silent), and only falls back to importing the exported file if the
 *      sealed kit is gone.
 *
 * # File formats
 *
 * There are two on-disk shapes, sharing one device-independent core:
 *
 * ## The self-contained recovery blob (the exported file, and the plaintext the
 * at-rest kit protects)
 *
 * ```
 *   +--------+------------------------------------------------------+
 *   | offset | field                                                |
 *   +--------+------------------------------------------------------+
 *   |   0    | magic "USRK" (4)                                     |
 *   |   4    | format version u8 (= [BLOB_VERSION])                |
 *   |   5    | R length u16 (BE) (= [Crypto.KEK_BYTES] today)      |
 *   |   7    | R bytes (the raw recovery secret)                   |
 *   |  ...   | envelope length u32 (BE)                            |
 *   |  ...   | [BackupEnvelope] bytes (see below)                  |
 *   +--------+------------------------------------------------------+
 * ```
 *
 * The trailing envelope is a standard [VaultRecoveryEnvelope] / [BackupEnvelope]
 * + [AesGcmPassphraseCodec] file whose PASSPHRASE is `R` (base64 form, via
 * [RecoveryKeyCodec.encode]) and whose PAYLOAD is the vault KEK material. `R` is
 * carried in the clear IN THE SAME blob on purpose: this is a self-contained
 * disaster-recovery artifact — whoever holds the file can open the vault (that
 * is the stated, honest property). Its confidentiality comes from WHERE the user
 * keeps it, and — for the at-rest copy — from the wrap-key seal below. No new
 * codec, KDF, or envelope fork: it reuses the suite's existing ones verbatim.
 *
 * ## The at-rest sealed kit (app-private `recovery_kit.bin`)
 *
 * The self-contained blob above, GCM-sealed under [RecoveryWrapKey]:
 *
 * ```
 *   +--------+------------------------------------------------------+
 *   | offset | field                                                |
 *   +--------+------------------------------------------------------+
 *   |   0    | kit version u8 (= [KIT_VERSION])                    |
 *   |   1    | wrap iv length u32 (BE)                             |
 *   |  ...   | wrap iv bytes                                       |
 *   |  ...   | wrapped ciphertext length u32 (BE)                 |
 *   |  ...   | GCM(wrapKey, self-contained blob) + tag            |
 *   +--------+------------------------------------------------------+
 * ```
 *
 * The framing mirrors passgen's `receipts.bin` so the whole suite has one
 * on-disk shape for a wrap-key-sealed payload.
 *
 * # Contract
 *
 * The four vault apps call ONLY the public functions here. Nothing in this class
 * renders a key or accepts a typed key; it is pure I/O + crypto. Ownership: every
 * `kekMaterial: ByteArray` returned is the caller's to use and wipe; every
 * `kekMaterial` passed in is copied internally, so the caller still owns and must
 * wipe its own buffer.
 */
object RecoveryFile {

    // ----- self-contained blob framing -------------------------------------

    /** Magic for the self-contained recovery blob (exported + kit plaintext). */
    private val BLOB_MAGIC: ByteArray = byteArrayOf(0x55, 0x53, 0x52, 0x4B) // "USRK"

    /** Self-contained blob format version. */
    private const val BLOB_VERSION: Int = 1

    // ----- at-rest kit framing ---------------------------------------------

    /** App-private filename for the sealed at-rest kit. */
    const val KIT_FILE: String = "recovery_kit.bin"

    /** At-rest sealed-kit format version. */
    private const val KIT_VERSION: Int = 1

    // ----- envelope conventions --------------------------------------------

    /**
     * App id recorded in the inner envelope header. The recovery blob is
     * app-agnostic at this layer (the caller's KEK material is opaque), so a
     * single suite-wide id is used; the outer [BLOB_MAGIC] already scopes the
     * file to "understory recovery kit".
     */
    private const val ENVELOPE_APP_ID: String = "com.understory.recovery"

    /**
     * Schema version for the inner envelope payload. >= the split-key floor so
     * [VaultRecoveryEnvelope] treats it as a modern (distinct-key) file, not a
     * legacy KEK-as-recovery one.
     */
    private const val ENVELOPE_SCHEMA: Int = VaultRecoveryEnvelope.SchemaVersions.SPLIT_KEY_MIN

    /** Guards the u32 length fields against a hostile/corrupt file → OOM. */
    private const val MAX_FIELD_BYTES: Int = 64 * 1024 * 1024

    // -----------------------------------------------------------------------
    // 1. SELF-SEAL
    // -----------------------------------------------------------------------

    /**
     * Generate `R`, build the self-contained recovery blob from `R` +
     * [kekMaterial], and write it to app-private storage encrypted under the
     * non-auth [RecoveryWrapKey]. No UI. Overwrites any existing kit atomically.
     *
     * @param ctx         app context (uses [Context.getFilesDir]).
     * @param appId       the calling app's id — accepted for call-site clarity
     *                    and forward compatibility; the on-disk kit is scoped by
     *                    per-app sandbox + [RecoveryWrapKey], so it does not gate
     *                    behavior today.
     * @param kekMaterial the vault KEK material to protect. COPIED internally;
     *                    the caller still owns and must wipe its own array.
     */
    fun seal(ctx: Context, appId: String, kekMaterial: ByteArray) {
        val blob = buildSelfContainedBlob(kekMaterial)
        try {
            val enc = RecoveryWrapKey.cipherForEncrypt()
            val wrappedIv = enc.iv
            val wrappedCt = enc.doFinal(blob)

            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { dos ->
                dos.writeByte(KIT_VERSION)
                dos.writeInt(wrappedIv.size); dos.write(wrappedIv)
                dos.writeInt(wrappedCt.size); dos.write(wrappedCt)
            }
            atomicWrite(ctx, out.toByteArray())
        } finally {
            Crypto.wipe(blob)
        }
    }

    /** True if an at-rest sealed kit exists in app-private storage. */
    fun hasSealedKit(ctx: Context): Boolean = File(ctx.filesDir, KIT_FILE).exists()

    /**
     * Unwrap the at-rest kit and return the KEK material for a silent same-device
     * re-bind. Returns null if the kit is absent, or undecryptable (wrap key
     * gone / file corrupt) — the caller then falls back to [importKit]. Never
     * throws for the absent/corrupt cases; a malformed inner blob is treated the
     * same as undecryptable.
     */
    fun readKekFromSealedKit(ctx: Context): ByteArray? {
        val f = File(ctx.filesDir, KIT_FILE)
        if (!f.exists()) return null
        return runCatching {
            val raw = f.readBytes()
            val (wrappedIv, wrappedCt) = DataInputStream(ByteArrayInputStream(raw)).use { dis ->
                val v = dis.readUnsignedByte()
                require(v == KIT_VERSION) { "unexpected kit version $v" }
                val ivLen = dis.readInt()
                require(ivLen in 1..MAX_FIELD_BYTES) { "kit iv length out of range: $ivLen" }
                val iv = ByteArray(ivLen).also { dis.readFully(it) }
                val ctLen = dis.readInt()
                require(ctLen in 1..MAX_FIELD_BYTES) { "kit ct length out of range: $ctLen" }
                val ct = ByteArray(ctLen).also { dis.readFully(it) }
                iv to ct
            }
            val blob = RecoveryWrapKey.cipherForDecrypt(wrappedIv).doFinal(wrappedCt)
            try {
                parseSelfContainedBlob(blob)
            } finally {
                Crypto.wipe(blob)
            }
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // 2. EXPORT (off-device disaster recovery)
    // -----------------------------------------------------------------------

    /**
     * Write the SELF-CONTAINED recovery file to [out] (a SAF `CreateDocument`
     * stream). Device-independent: it carries `R` plus the `R`-encrypted KEK
     * material, so it restores on a brand-new device. Opaque bytes only — never
     * displayed.
     *
     * The exported bytes are exactly the plaintext that the at-rest kit protects,
     * re-minted here with a FRESH `R` so the exported file and the in-vault kit do
     * not share a recovery secret. [out] is NOT closed (the SAF caller owns it).
     *
     * @param kekMaterial the vault KEK material to embed. COPIED internally; the
     *                    caller still owns and must wipe its own array.
     */
    fun exportKit(ctx: Context, out: OutputStream, kekMaterial: ByteArray) {
        val blob = buildSelfContainedBlob(kekMaterial)
        try {
            out.write(blob)
            out.flush()
        } finally {
            Crypto.wipe(blob)
        }
    }

    // -----------------------------------------------------------------------
    // 3. RESTORE
    // -----------------------------------------------------------------------

    /**
     * Parse an exported recovery file from [input] and return the KEK material
     * (restore on any device). The user never types a key — `R` travels inside
     * the file. Throws [IllegalArgumentException] (or an envelope/GCM exception)
     * on a malformed, truncated, or corrupt file. [input] is NOT closed (the SAF
     * caller owns it). The returned array is the caller's to use and wipe.
     */
    fun importKit(input: InputStream): ByteArray =
        parseSelfContainedBlob(input.readAllBytesCompat())

    // -----------------------------------------------------------------------
    // 4. RESEAL (after a new bind)
    // -----------------------------------------------------------------------

    /**
     * Re-seal the at-rest kit with a fresh `R` after the vault has been re-bound
     * to new [kekMaterial] (e.g. following a silent re-bind or a key rotation).
     * Identical to [seal]; named separately so call sites read intentionally. The
     * caller still owns and must wipe [kekMaterial].
     */
    fun reseal(ctx: Context, appId: String, kekMaterial: ByteArray) =
        seal(ctx, appId, kekMaterial)

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    /**
     * Mint a fresh `R` and produce the device-independent self-contained blob:
     * `USRK || ver || R || VaultRecoveryEnvelope(payload=kekMaterial, key=R)`.
     * [kekMaterial] is copied for the envelope (which wipes its copy); the
     * caller's array is untouched.
     */
    private fun buildSelfContainedBlob(kekMaterial: ByteArray): ByteArray {
        val r = Crypto.randomBytes(Crypto.KEK_BYTES)
        // R as base64 chars = the codec passphrase (matches RecoveryKeyCodec so
        // there is one recovery-string shape across the suite).
        val rChars = RecoveryKeyCodec.encode(r)
        try {
            val envelopeBytes = ByteArrayOutputStream().use { env ->
                // writeEncrypted wipes the payload copy it is handed; give it a
                // copy so the caller's kekMaterial survives.
                VaultRecoveryEnvelope.writeEncrypted(
                    out = env,
                    appId = ENVELOPE_APP_ID,
                    schemaVersion = ENVELOPE_SCHEMA,
                    plaintext = kekMaterial.copyOf(),
                    recoveryKeyChars = rChars,
                )
                env.toByteArray()
            }

            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { dos ->
                dos.write(BLOB_MAGIC)
                dos.writeByte(BLOB_VERSION)
                dos.writeShort(r.size)
                dos.write(r)
                dos.writeInt(envelopeBytes.size)
                dos.write(envelopeBytes)
            }
            return out.toByteArray()
        } finally {
            Crypto.wipe(r)
            Crypto.wipe(rChars)
        }
    }

    /**
     * Parse a self-contained blob and return the embedded KEK material. Reads `R`
     * from the blob, reconstructs the codec passphrase, and decrypts the trailing
     * envelope. Throws on malformed framing, and the envelope's GCM auth throws on
     * a tampered/corrupt payload.
     */
    private fun parseSelfContainedBlob(blob: ByteArray): ByteArray {
        val dis = DataInputStream(ByteArrayInputStream(blob))
        val magic = ByteArray(4).also { dis.readFully(it) }
        require(magic.contentEquals(BLOB_MAGIC)) { "not an understory recovery file" }
        val version = dis.readUnsignedByte()
        require(version == BLOB_VERSION) { "unsupported recovery file version $version" }
        val rLen = dis.readUnsignedShort()
        require(rLen in 1..MAX_FIELD_BYTES) { "R length out of range: $rLen" }
        val r = ByteArray(rLen).also { dis.readFully(it) }
        val envLen = dis.readInt()
        require(envLen in 1..MAX_FIELD_BYTES) { "envelope length out of range: $envLen" }
        val envelopeBytes = ByteArray(envLen).also { dis.readFully(it) }
        require(dis.read() == -1) { "trailing bytes after recovery file payload" }

        val rChars = RecoveryKeyCodec.encode(r)
        try {
            val opened = VaultRecoveryEnvelope.open(ByteArrayInputStream(envelopeBytes))
            return VaultRecoveryEnvelope.decrypt(opened, rChars)
        } finally {
            Crypto.wipe(r)
            Crypto.wipe(rChars)
        }
    }

    /**
     * Atomically write the sealed kit: write a temp then rename over the target,
     * so a process kill mid-write can't leave a torn kit. Mirrors passgen's
     * `Vault.atomicReplace` without depending on app code.
     */
    private fun atomicWrite(ctx: Context, bytes: ByteArray) {
        val tmp = File(ctx.filesDir, "$KIT_FILE.tmp")
        val target = File(ctx.filesDir, KIT_FILE)
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(target)) {
            // renameTo can fail if the target exists on some filesystems; fall
            // back to delete-then-rename, then a copy as a last resort.
            target.delete()
            if (!tmp.renameTo(target)) {
                target.outputStream().use { it.write(bytes) }
                tmp.delete()
            }
        }
    }
}
