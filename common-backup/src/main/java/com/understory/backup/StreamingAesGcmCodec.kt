package com.understory.backup

import com.understory.security.Crypto
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-GCM codec for arbitrarily large backups.
 *
 * The single-shot [AesGcmPassphraseCodec] needs the full plaintext in
 * memory. That works for vault snapshots (~hundreds of KiB) but not
 * for device-wide snapshots that include user-dir content (multi-GiB
 * Pictures + Music dirs). This codec encrypts a stream of
 * [InputStream] -> [OutputStream] in fixed-size chunks; memory cost
 * is bounded by [DEFAULT_CHUNK_SIZE] regardless of total payload.
 *
 * On-disk layout:
 *
 *   header (cleartext, fixed-size + variable AAD region):
 *     +-------+--------+-----------------------------------------+
 *     | off   | size   | field                                   |
 *     +-------+--------+-----------------------------------------+
 *     |   0   |  8     | magic = "USTRSTRM" (ASCII)              |
 *     |   8   |  1     | version (0x01)                          |
 *     |   9   |  1     | flags (reserved, 0x00)                  |
 *     |  10   |  2     | reserved (0x0000)                       |
 *     |  12   |  4     | chunk_plaintext_size (BE, e.g. 1<<20)   |
 *     |  16   | 32     | argon2id salt                           |
 *     |  48   | 12     | nonce_prefix (random)                   |
 *     |  60   |  4     | aad_length (BE)                         |
 *     |  64   | aad_length | external AAD (e.g. envelope header) |
 *     |  64+aad | ...  | sequence of chunks                      |
 *     +-------+--------+-----------------------------------------+
 *
 *   each chunk:
 *     +-------+--------+-----------------------------------------+
 *     |   0   |  4     | length (BE). High bit (0x80000000) set  |
 *     |       |        | iff this is the final chunk.            |
 *     |   4   | length & 0x7FFFFFFF | ciphertext + 16-byte GCM tag |
 *     +-------+--------+-----------------------------------------+
 *
 * Per-chunk IV construction:
 *   - nonce_prefix is 12 bytes random per stream.
 *   - For chunk N, IV = nonce_prefix XOR (4 zero bytes || counter_be32(N)).
 *     Effectively: the last 4 bytes of nonce_prefix XOR counter_be32(N).
 *   - This guarantees no IV reuse within a stream up to 2^32 chunks.
 *     At 1 MiB/chunk that's 4 PiB per stream — sufficient.
 *   - Different streams have independent nonce_prefix from the CSPRNG,
 *     so cross-stream IV collisions are negligible (12 random bytes).
 *
 * Per-chunk AAD construction:
 *   - chunk_aad = stream_aad || counter_be32(N) || final_flag_byte
 *   - stream_aad is the caller-supplied AAD (typically the envelope
 *     header bytes, so the codec ciphertext is bound to the
 *     surrounding envelope's identity).
 *   - counter_be32(N) prevents chunk reordering: swapping chunks 5
 *     and 6 makes both fail GCM verification.
 *   - final_flag_byte (0x01 for last chunk, 0x00 otherwise)
 *     prevents truncation: an attacker who drops the final chunk
 *     also has to forge a "final" tag on the chunk before — which
 *     they can't without the key.
 *
 * Threat properties:
 *   - Confidentiality: AES-256-GCM per chunk under Argon2id-derived key.
 *   - Integrity: per-chunk GCM tag covers chunk ciphertext + AAD.
 *   - Reorder-resistance: counter in AAD.
 *   - Truncation-resistance: final-flag in AAD.
 *   - Replay across streams: independent random nonce_prefix per stream.
 *
 * Memory: O(chunk_size). Default 1 MiB. Caller can shrink for memory-
 * constrained foreground services or grow for throughput on devices
 * with the headroom; 256 KiB - 4 MiB is the practical range.
 *
 * Compatibility: this is intentionally NOT a [BackupCodec]. The
 * [BackupCodec] interface is buffer-in/buffer-out, which is the wrong
 * shape for streaming. Streaming snapshots use this codec directly
 * and write a custom wrapper file (not [BackupEnvelope]) — see the
 * snapshot caller for the wrapper layout. The two formats coexist;
 * single-shot vault snapshots stay on the envelope path.
 */
object StreamingAesGcmCodec {

    /** ASCII bytes "USTRSTRM" — the stream-format magic. Bound to the
     *  format version below; bumping the version starts a new magic
     *  so old readers can't half-parse a new file. */
    val MAGIC: ByteArray = "USTRSTRM".toByteArray(Charsets.US_ASCII)
    const val VERSION: Byte = 0x01

    /** 1 MiB — balances memory cost (one chunk in-flight per direction)
     *  against per-chunk overhead (GCM tag + length prefix = 20 bytes,
     *  ~0.002% of plaintext). */
    const val DEFAULT_CHUNK_SIZE: Int = 1 shl 20

    /** Min chunk size lets callers run integration tests without
     *  needing megabytes of input; below 1 KiB the per-chunk overhead
     *  becomes meaningful so we cap there. */
    const val MIN_CHUNK_SIZE: Int = 1024

    /** Above 16 MiB, allocating two chunks (one in-flight encrypt + one
     *  output buffer) starts to risk OOM on low-end Android devices.
     *  No real throughput gain past ~4 MiB anyway — JCE's per-call
     *  overhead is dominated by argon2 amortization, not block size. */
    const val MAX_CHUNK_SIZE: Int = 16 shl 20

    private const val SALT_BYTES = Crypto.SALT_BYTES         // 32
    /** AES-GCM standard nonce size. Matches Crypto's internal IV_BYTES
     *  (private), so we redeclare the same value here rather than
     *  widening Crypto's API surface for a single read. */
    private const val NONCE_PREFIX_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val FINAL_BIT_MASK: Int = 0x7FFF_FFFF.inv()  // 0x80000000
    private const val LENGTH_MASK: Int = 0x7FFF_FFFF
    private const val HEADER_FIXED_BYTES = 8 + 1 + 1 + 2 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES + 4

    /**
     * Encrypt [plaintext] -> [ciphertext], chunk by chunk.
     *
     * Closes neither stream — caller owns lifecycle (typically wraps
     * in try-with-resources / `use`). Caller must also wipe
     * [passphrase] after this call returns.
     *
     * [externalAad] is included verbatim in the cleartext header (so
     * the reader has it at decrypt time without re-supplying) AND
     * mixed into every chunk's GCM AAD, binding the ciphertext to the
     * surrounding context (typically a [BackupEnvelope.Header]).
     */
    fun encrypt(
        plaintext: InputStream,
        ciphertext: OutputStream,
        passphrase: CharArray,
        externalAad: ByteArray = ByteArray(0),
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ) {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "chunkSize $chunkSize outside [$MIN_CHUNK_SIZE, $MAX_CHUNK_SIZE]"
        }
        require(externalAad.size <= MAX_AAD_BYTES) {
            "externalAad too large: ${externalAad.size} > $MAX_AAD_BYTES"
        }

        val salt = Crypto.randomBytes(SALT_BYTES)
        val noncePrefix = Crypto.randomBytes(NONCE_PREFIX_BYTES)
        val derivedKey = Crypto.argon2id(passphrase, salt)
        try {
            writeHeader(ciphertext, salt, noncePrefix, chunkSize, externalAad)

            val keySpec = SecretKeySpec(derivedKey, "AES")
            val buf = ByteArray(chunkSize)
            // Look-ahead pattern: read the next chunk before emitting
            // the current one so we know whether the current chunk is
            // final at write-time. Without this we can't set the final
            // flag correctly without buffering everything.
            var read = readFully(plaintext, buf, 0, chunkSize)
            var counter = 0
            while (true) {
                val nextBuf = ByteArray(chunkSize)
                val nextRead = if (read == chunkSize)
                    readFully(plaintext, nextBuf, 0, chunkSize)
                else 0
                val isFinal = (read < chunkSize) || nextRead == 0
                writeChunk(
                    ciphertext, keySpec, noncePrefix, counter,
                    buf, read, externalAad, isFinal,
                )
                if (isFinal) break
                System.arraycopy(nextBuf, 0, buf, 0, nextRead)
                read = nextRead
                counter++
                require(counter >= 0) { "chunk counter overflow (>= 2^31 chunks)" }
            }

            ciphertext.flush()
        } finally {
            Crypto.wipe(derivedKey)
        }
    }

    /**
     * Decrypt [ciphertext] -> [plaintext]. Verifies every chunk's GCM
     * tag, checks the final-flag against actual EOF, and refuses to
     * emit any plaintext past a chunk that fails verification.
     *
     * Returns the externalAad that was bound at encrypt time, so the
     * caller can verify it matches their expected value (defense
     * against header-substitution attacks where the attacker swaps
     * one snapshot's body under a different envelope's header).
     */
    fun decrypt(
        ciphertext: InputStream,
        plaintext: OutputStream,
        passphrase: CharArray,
    ): ByteArray {
        val (salt, noncePrefix, chunkSize, externalAad) = readHeader(ciphertext)
        val derivedKey = Crypto.argon2id(passphrase, salt)
        try {
            val keySpec = SecretKeySpec(derivedKey, "AES")
            var counter = 0
            var sawFinal = false
            // Pre-size to expected max chunk size + tag; reused across
            // chunks. Decryption cipher emits plaintext that is exactly
            // (ciphertext_length - tag_size) bytes.
            val ciphertextBuf = ByteArray(chunkSize + GCM_TAG_BYTES)
            while (!sawFinal) {
                val lengthHeader = readIntBE(ciphertext)
                val isFinal = (lengthHeader and FINAL_BIT_MASK) != 0
                val ctLen = lengthHeader and LENGTH_MASK
                // Allow ctLen == GCM_TAG_BYTES (a final chunk encrypting
                // empty plaintext is exactly the 16-byte tag). Reject
                // smaller — those can't be valid AES-GCM output.
                if (ctLen < GCM_TAG_BYTES) {
                    throw IOException("chunk length $ctLen < tag size $GCM_TAG_BYTES")
                }
                if (ctLen > ciphertextBuf.size) {
                    throw IOException(
                        "chunk length $ctLen exceeds chunkSize+tag ${ciphertextBuf.size}"
                    )
                }
                val n = readFully(ciphertext, ciphertextBuf, 0, ctLen)
                if (n != ctLen) {
                    throw EOFException("truncated chunk: wanted $ctLen, got $n")
                }
                val plain = decryptChunk(
                    keySpec, noncePrefix, counter,
                    ciphertextBuf, ctLen, externalAad, isFinal,
                )
                plaintext.write(plain)
                if (isFinal) {
                    sawFinal = true
                    // Ensure no trailing bytes after the final chunk.
                    if (ciphertext.read() != -1) {
                        throw IOException("trailing bytes after final chunk")
                    }
                }
                counter++
                require(counter >= 0) { "chunk counter overflow" }
            }
            plaintext.flush()
            return externalAad
        } finally {
            Crypto.wipe(derivedKey)
        }
    }

    // ---------- header helpers ----------

    private const val MAX_AAD_BYTES: Int = 64 * 1024

    private fun writeHeader(
        out: OutputStream,
        salt: ByteArray,
        noncePrefix: ByteArray,
        chunkSize: Int,
        externalAad: ByteArray,
    ) {
        out.write(MAGIC)
        out.write(byteArrayOf(VERSION, 0x00, 0x00, 0x00))   // version + flags + reserved
        out.write(intBE(chunkSize))
        out.write(salt)
        out.write(noncePrefix)
        out.write(intBE(externalAad.size))
        if (externalAad.isNotEmpty()) out.write(externalAad)
    }

    private data class Header(
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val chunkSize: Int,
        val externalAad: ByteArray,
    )

    private fun readHeader(input: InputStream): Header {
        val fixed = ByteArray(HEADER_FIXED_BYTES)
        if (readFully(input, fixed, 0, HEADER_FIXED_BYTES) != HEADER_FIXED_BYTES) {
            throw EOFException("truncated stream header")
        }
        if (!fixed.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw IOException("bad magic; not a USTRSTRM stream")
        }
        if (fixed[8] != VERSION) {
            throw IOException("unsupported version: 0x${"%02x".format(fixed[8])}")
        }
        val chunkSize = intFromBE(fixed, 12)
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            throw IOException("invalid chunkSize in header: $chunkSize")
        }
        val salt = fixed.copyOfRange(16, 16 + SALT_BYTES)
        val noncePrefix = fixed.copyOfRange(48, 48 + NONCE_PREFIX_BYTES)
        val aadLen = intFromBE(fixed, 60)
        if (aadLen < 0 || aadLen > MAX_AAD_BYTES) {
            throw IOException("invalid aad length: $aadLen")
        }
        val aad = ByteArray(aadLen)
        if (aadLen > 0 && readFully(input, aad, 0, aadLen) != aadLen) {
            throw EOFException("truncated AAD region")
        }
        return Header(salt, noncePrefix, chunkSize, aad)
    }

    // ---------- chunk crypto ----------

    private fun writeChunk(
        out: OutputStream,
        key: SecretKeySpec,
        noncePrefix: ByteArray,
        counter: Int,
        buf: ByteArray,
        len: Int,
        externalAad: ByteArray,
        isFinal: Boolean,
    ) {
        val iv = chunkIv(noncePrefix, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(chunkAad(externalAad, counter, isFinal))
        val ct = cipher.doFinal(buf, 0, len)
        val lengthHeader = ct.size or (if (isFinal) FINAL_BIT_MASK else 0)
        out.write(intBE(lengthHeader))
        out.write(ct)
    }

    private fun decryptChunk(
        key: SecretKeySpec,
        noncePrefix: ByteArray,
        counter: Int,
        ct: ByteArray,
        ctLen: Int,
        externalAad: ByteArray,
        isFinal: Boolean,
    ): ByteArray {
        val iv = chunkIv(noncePrefix, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(chunkAad(externalAad, counter, isFinal))
        return cipher.doFinal(ct, 0, ctLen)
    }

    /** Chunk IV = noncePrefix with the trailing 4 bytes XOR-ed by the
     *  big-endian counter. Per-chunk uniqueness within a stream;
     *  per-stream uniqueness comes from noncePrefix being CSPRNG. */
    private fun chunkIv(noncePrefix: ByteArray, counter: Int): ByteArray {
        val iv = noncePrefix.copyOf()
        iv[8] = (iv[8].toInt() xor ((counter ushr 24) and 0xFF)).toByte()
        iv[9] = (iv[9].toInt() xor ((counter ushr 16) and 0xFF)).toByte()
        iv[10] = (iv[10].toInt() xor ((counter ushr 8) and 0xFF)).toByte()
        iv[11] = (iv[11].toInt() xor (counter and 0xFF)).toByte()
        return iv
    }

    /** Per-chunk AAD = externalAad || counter_be32 || final_flag_byte.
     *  Bound into GCM tag computation so any tampering fails. */
    private fun chunkAad(externalAad: ByteArray, counter: Int, isFinal: Boolean): ByteArray {
        val out = ByteArray(externalAad.size + 4 + 1)
        System.arraycopy(externalAad, 0, out, 0, externalAad.size)
        out[externalAad.size + 0] = ((counter ushr 24) and 0xFF).toByte()
        out[externalAad.size + 1] = ((counter ushr 16) and 0xFF).toByte()
        out[externalAad.size + 2] = ((counter ushr 8) and 0xFF).toByte()
        out[externalAad.size + 3] = (counter and 0xFF).toByte()
        out[externalAad.size + 4] = if (isFinal) 0x01 else 0x00
        return out
    }

    // ---------- io helpers ----------

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun readIntBE(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if ((b0 or b1 or b2 or b3) < 0) throw EOFException("truncated chunk length")
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun intFromBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
