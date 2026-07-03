package com.understory.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class StreamingAesGcmCodecTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    /** Pure-JVM tests can spin pretty light Argon2 params. The codec
     *  itself doesn't pick params — Crypto.argon2id does — so these
     *  tests run with the suite's compile-time constants. ~64 MiB
     *  per derive but only one derive per stream, so total test
     *  runtime stays under a few seconds. */
    private val testChunkSize = 4096

    @Test fun roundtrip_smallPlaintext() {
        val plaintext = "hello world".toByteArray()
        val ct = encryptToBytes(plaintext)
        val pt = decryptFromBytes(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test fun roundtrip_emptyPlaintext() {
        val ct = encryptToBytes(ByteArray(0))
        val pt = decryptFromBytes(ct)
        assertEquals(0, pt.size)
    }

    @Test fun roundtrip_exactlyOneChunk() {
        // Plaintext that fits exactly one chunk; verifies the
        // boundary case where the look-ahead read returns 0.
        val plaintext = Random(1).nextBytes(testChunkSize)
        val ct = encryptToBytes(plaintext)
        val pt = decryptFromBytes(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test fun roundtrip_chunkSizePlusOne() {
        // Exercises two-chunk path with a tiny final chunk.
        val plaintext = Random(2).nextBytes(testChunkSize + 1)
        val ct = encryptToBytes(plaintext)
        val pt = decryptFromBytes(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test fun roundtrip_multipleFullChunks() {
        // Five full chunks; exercises counter increment correctness
        // for multi-chunk streams.
        val plaintext = Random(3).nextBytes(testChunkSize * 5)
        val ct = encryptToBytes(plaintext)
        val pt = decryptFromBytes(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test fun roundtrip_largePlaintext() {
        // 100 chunks * 4 KiB = 400 KiB. Plenty to verify no
        // counter-related drift across many iterations without making
        // the test slow.
        val plaintext = Random(4).nextBytes(testChunkSize * 100)
        val ct = encryptToBytes(plaintext)
        val pt = decryptFromBytes(ct)
        assertArrayEquals(plaintext, pt)
    }

    @Test fun externalAad_isReturnedFromDecrypt() {
        val aad = "envelope-header-bytes".toByteArray()
        val plaintext = "payload".toByteArray()
        val ct = encryptToBytes(plaintext, externalAad = aad)
        val out = ByteArrayOutputStream()
        val returnedAad = StreamingAesGcmCodec.decrypt(
            ByteArrayInputStream(ct), out, passphrase.copyOf(),
        )
        assertArrayEquals(aad, returnedAad)
        assertArrayEquals(plaintext, out.toByteArray())
    }

    @Test fun differentNoncePrefix_acrossEncryptions() {
        // Two encryptions of the same plaintext under the same
        // passphrase MUST produce different ciphertexts, because the
        // salt and nonce_prefix are both freshly random per encrypt.
        val plaintext = "same".toByteArray()
        val ct1 = encryptToBytes(plaintext)
        val ct2 = encryptToBytes(plaintext)
        // Headers differ (random salt + nonce prefix), bodies differ.
        assertNotEquals(ct1.toList(), ct2.toList())
    }

    @Test fun tamper_chunkBody_failsGcm() {
        val plaintext = Random(5).nextBytes(testChunkSize * 3)
        val ct = encryptToBytes(plaintext)
        // Flip a byte well past the header in chunk 1's body.
        val tampered = ct.copyOf()
        // Header is 64 + aad_len bytes, then a 4-byte length, then
        // chunk bytes. Flipping at offset 200 lands inside chunk 0's
        // ciphertext for typical headers.
        tampered[200] = (tampered[200].toInt() xor 0x01).toByte()
        try {
            decryptFromBytes(tampered)
            fail("expected GCM verification failure")
        } catch (e: Exception) {
            // GCM tag failure surfaces as AEADBadTagException wrapped
            // in javax.crypto.BadPaddingException on some JCE
            // providers. Either is acceptable; we just need decrypt
            // to refuse the tampered stream.
            assertTrue(
                "got ${e.javaClass.simpleName}: ${e.message}",
                e is AEADBadTagException ||
                    e.cause is AEADBadTagException ||
                    e.javaClass.simpleName.contains("BadPadding") ||
                    e.message?.contains("mac") == true ||
                    e.message?.contains("tag") == true ||
                    e.message?.contains("length") == true,
            )
        }
    }

    @Test fun tamper_finalFlag_failsGcm() {
        // Encrypt three chunks. Flip the final-flag of the last chunk
        // (clear the high bit of its length prefix). Decrypt should
        // fail because the per-chunk AAD includes the flag.
        val plaintext = Random(6).nextBytes(testChunkSize * 3)
        val ct = encryptToBytes(plaintext)

        // Find the start of the last chunk by walking the length
        // prefixes. Header bytes: HEADER_FIXED + aad (we use empty
        // aad here so just HEADER_FIXED).
        val tampered = ct.copyOf()
        var pos = HEADER_FIXED_BYTES
        var lastLenPos = -1
        while (pos < tampered.size) {
            val lenHeader = readBE(tampered, pos)
            lastLenPos = pos
            val ctLen = lenHeader and 0x7FFF_FFFF
            pos += 4 + ctLen
        }
        assertTrue("walked past EOF", pos == tampered.size)
        assertTrue("found a chunk", lastLenPos >= 0)

        // Clear the high bit of the last chunk's length header.
        tampered[lastLenPos] = (tampered[lastLenPos].toInt() and 0x7F).toByte()
        try {
            decryptFromBytes(tampered)
            fail("expected failure on tampered final-flag")
        } catch (e: Exception) {
            // Either GCM rejects (because chunk AAD now claims
            // "not final" while encryptor used "final"), or the
            // truncation check triggers (we never see a final chunk
            // before EOF). Either is correct fail-closed behavior.
            // Just verify SOMETHING refuses the stream.
        }
    }

    @Test fun truncation_failsBeforeEmittingFinalPlaintext() {
        // Drop the final chunk. Decrypt should fail with EOF before
        // returning successfully. Plaintext written into the output
        // stream up to the truncation point may or may not be partial
        // depending on chunk size — the contract is "won't return
        // success on truncated input."
        val plaintext = Random(7).nextBytes(testChunkSize * 3)
        val ct = encryptToBytes(plaintext)
        // Walk to the start of the last chunk (length prefix), keep
        // everything before it.
        var pos = HEADER_FIXED_BYTES
        var lastLenPos = pos
        while (pos < ct.size) {
            lastLenPos = pos
            val lenHeader = readBE(ct, pos)
            val ctLen = lenHeader and 0x7FFF_FFFF
            pos += 4 + ctLen
        }
        val truncated = ct.copyOfRange(0, lastLenPos)
        try {
            decryptFromBytes(truncated)
            fail("expected EOF on truncated stream")
        } catch (e: Exception) {
            // EOFException or wrapped equivalent.
            assertTrue(
                "got ${e.javaClass.simpleName}: ${e.message}",
                e is java.io.EOFException ||
                    e.cause is java.io.EOFException ||
                    e is IOException ||
                    e.message?.contains("EOF", ignoreCase = true) == true ||
                    e.message?.contains("trunc", ignoreCase = true) == true,
            )
        }
    }

    @Test fun trailingBytes_afterFinalChunk_fail() {
        // Append junk after the final chunk. Decrypt should refuse
        // even though the final chunk's GCM tag verifies.
        val ct = encryptToBytes("hello".toByteArray())
        val withTrailer = ct + byteArrayOf(0x42, 0x42, 0x42)
        try {
            decryptFromBytes(withTrailer)
            fail("expected failure on trailing bytes")
        } catch (e: IOException) {
            assertTrue(
                "expected 'trailing' message, got: ${e.message}",
                e.message?.contains("trailing") == true,
            )
        }
    }

    @Test fun wrongPassphrase_failsGcm() {
        val ct = encryptToBytes("hello".toByteArray())
        val out = ByteArrayOutputStream()
        try {
            StreamingAesGcmCodec.decrypt(
                ByteArrayInputStream(ct), out,
                "wrong passphrase".toCharArray(),
            )
            fail("expected GCM verification failure with wrong passphrase")
        } catch (e: Exception) {
            // Argon2 derives a different key, so GCM tag check fails.
            assertTrue(
                "got ${e.javaClass.simpleName}: ${e.message}",
                e is AEADBadTagException ||
                    e.cause is AEADBadTagException ||
                    e.javaClass.simpleName.contains("BadPadding"),
            )
        }
    }

    @Test fun badMagic_failsCleanly() {
        // First 8 bytes are the magic; corrupting it should give a
        // clear "bad magic" error before any crypto runs.
        val ct = encryptToBytes("x".toByteArray())
        ct[0] = 'Z'.code.toByte()
        try {
            decryptFromBytes(ct)
            fail("expected bad-magic error")
        } catch (e: IOException) {
            assertTrue(
                "expected 'magic' message, got: ${e.message}",
                e.message?.contains("magic", ignoreCase = true) == true,
            )
        }
    }

    @Test fun badVersion_failsCleanly() {
        val ct = encryptToBytes("x".toByteArray())
        ct[8] = 0x99.toByte()  // version byte
        try {
            decryptFromBytes(ct)
            fail("expected bad-version error")
        } catch (e: IOException) {
            assertTrue(
                "expected 'version' message, got: ${e.message}",
                e.message?.contains("version", ignoreCase = true) == true,
            )
        }
    }

    @Test fun rejectsChunkSize_belowMin() {
        try {
            val out = ByteArrayOutputStream()
            StreamingAesGcmCodec.encrypt(
                ByteArrayInputStream("x".toByteArray()), out,
                passphrase.copyOf(),
                chunkSize = 16,  // below MIN_CHUNK_SIZE
            )
            fail("expected IllegalArgumentException for tiny chunkSize")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun rejectsChunkSize_aboveMax() {
        try {
            val out = ByteArrayOutputStream()
            StreamingAesGcmCodec.encrypt(
                ByteArrayInputStream("x".toByteArray()), out,
                passphrase.copyOf(),
                chunkSize = 64 * 1024 * 1024,  // 64 MiB > MAX
            )
            fail("expected IllegalArgumentException for huge chunkSize")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ---------- helpers ----------

    /** HEADER_FIXED_BYTES from the codec: 8 magic + 1 version + 1 flags
     *  + 2 reserved + 4 chunk_size + 32 salt + 12 nonce + 4 aad_len. */
    private val HEADER_FIXED_BYTES = 8 + 1 + 1 + 2 + 4 + 32 + 12 + 4

    private fun encryptToBytes(
        plaintext: ByteArray,
        externalAad: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        StreamingAesGcmCodec.encrypt(
            ByteArrayInputStream(plaintext), out,
            passphrase.copyOf(),
            externalAad = externalAad,
            chunkSize = testChunkSize,
        )
        return out.toByteArray()
    }

    private fun decryptFromBytes(ct: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        StreamingAesGcmCodec.decrypt(
            ByteArrayInputStream(ct), out,
            passphrase.copyOf(),
        )
        return out.toByteArray()
    }

    private fun readBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
