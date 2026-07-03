package com.understory.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tests for [BackupEnvelope] — the on-disk container format every
 * Understory backup file uses. The envelope's contract is the union
 * of file-format integrity (magic / version / lengths) and AAD-binding
 * of the cleartext header to the ciphertext. Both ends are tested
 * here; the second is the security-critical one — header tamper must
 * invalidate decryption.
 *
 * Uses a dummy [BackupCodec] (no real crypto) so the tests focus on
 * envelope behavior rather than re-testing primitives that
 * [com.understory.security.CryptoTest] already covers. Real-codec
 * round-trip is tested separately in [AesGcmPassphraseCodecTest].
 */
class BackupEnvelopeTest {

    /**
     * No-op "codec" that XORs plaintext with the AAD's first byte and
     * checks AAD on decrypt. Just enough to prove the envelope wires
     * AAD through correctly. Production codecs use AES-GCM tag
     * binding; this test fake uses a recognizable XOR pattern.
     */
    private class FakeXorCodec : BackupCodec {
        override val id: Int = 99
        override val name: String = "fake-xor"
        var lastEncryptAad: ByteArray? = null
        var lastDecryptAad: ByteArray? = null

        class Key(val nonce: Byte) : BackupCodec.KeyMaterial {
            override fun wipe() {}
        }

        override fun encrypt(plaintext: ByteArray, aad: ByteArray, key: BackupCodec.KeyMaterial): ByteArray {
            require(key is Key)
            lastEncryptAad = aad.copyOf()
            val out = ByteArray(plaintext.size + 1)
            out[0] = key.nonce
            for (i in plaintext.indices) out[i + 1] = (plaintext[i].toInt() xor aad[0].toInt()).toByte()
            return out
        }

        override fun decrypt(ciphertext: ByteArray, aad: ByteArray, key: BackupCodec.KeyMaterial): ByteArray {
            require(key is Key)
            lastDecryptAad = aad.copyOf()
            require(ciphertext[0] == key.nonce) { "wrong key" }
            val out = ByteArray(ciphertext.size - 1)
            for (i in out.indices) out[i] = (ciphertext[i + 1].toInt() xor aad[0].toInt()).toByte()
            return out
        }
    }

    private fun sampleHeader() = BackupEnvelope.Header(
        appId = "com.understory.aegis",
        schemaVersion = 1,
        createdAtMs = 1_700_000_000_000L,
        label = "main vault",
        codecParams = mapOf("variant" to "aes-gcm-passphrase", "v" to "1"),
    )

    @Test
    fun writeThenParse_preservesHeaderAndCiphertext() {
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x42)
        val plaintext = "hello vault".toByteArray()
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), plaintext, key)

        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(codec.id, parsed.codecId)
        assertEquals("com.understory.aegis", parsed.header.appId)
        assertEquals(1, parsed.header.schemaVersion)
        assertEquals(1_700_000_000_000L, parsed.header.createdAtMs)
        assertEquals("main vault", parsed.header.label)
        assertEquals("aes-gcm-passphrase", parsed.header.codecParams["variant"])
        assertEquals("1", parsed.header.codecParams["v"])

        val recovered = BackupEnvelope.decryptPayload(parsed, codec, key)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun headerJsonIsAadOnEncrypt_andOnDecrypt_andIdentical() {
        // The whole point of feeding the header as AAD: any tamper of
        // the header bytes between write and read must invalidate the
        // ciphertext. Pin the contract that what the codec sees on
        // encrypt is byte-identical to what it sees on decrypt.
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), "x".toByteArray(), key)
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        BackupEnvelope.decryptPayload(parsed, codec, key)
        assertArrayEquals(codec.lastEncryptAad, codec.lastDecryptAad)
        assertArrayEquals(codec.lastEncryptAad, parsed.headerRaw)
    }

    @Test
    fun magicMismatchIsRejected() {
        val bytes = ByteArray(32)
        bytes[0] = 'X'.code.toByte()
        try {
            BackupEnvelope.parse(ByteArrayInputStream(bytes))
            fail("non-magic input must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), "x".toByteArray(), key)
        val bytes = out.toByteArray()
        // Magic is at 0..3; version is at byte 4.
        bytes[4] = 99
        try {
            BackupEnvelope.parse(ByteArrayInputStream(bytes))
            fail("unsupported version must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun trailingBytesAreRejected() {
        // Smuggling: appending data after the declared payload is a
        // class of attack future codecs shouldn't have to defend
        // against individually. Envelope rejects any trailing byte.
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), "x".toByteArray(), key)
        val tampered = out.toByteArray() + byteArrayOf(0x00)
        try {
            BackupEnvelope.parse(ByteArrayInputStream(tampered))
            fail("trailing bytes must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun codecIdMismatchOnDecryptIsRejected() {
        // The envelope records which codec wrote it. If the caller
        // tries to decrypt with a *different* codec, the orchestrator
        // must catch it before the decrypt call — otherwise wrong-
        // codec decryption could plausibly succeed and yield garbage.
        val codecA = FakeXorCodec()
        val codecB = object : BackupCodec {
            override val id = 100
            override val name = "fake-other"
            override fun encrypt(p: ByteArray, a: ByteArray, k: BackupCodec.KeyMaterial) = p
            override fun decrypt(c: ByteArray, a: ByteArray, k: BackupCodec.KeyMaterial) = c
        }
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codecA, sampleHeader(), "x".toByteArray(), key)
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        try {
            BackupEnvelope.decryptPayload(parsed, codecB, key)
            fail("decrypting envelope with wrong codec must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun headerEncodeDecode_roundTripsSpecialChars() {
        // The hand-rolled JSON encoder/decoder must handle:
        //  - quotes / backslashes (escape correctly)
        //  - newlines / tabs (escape as \n / \t)
        //  - non-ASCII Unicode (must round-trip byte-for-byte through
        //    UTF-8 since the AAD is bytes, not chars)
        val tricky = BackupEnvelope.Header(
            appId = "com.understory.test",
            schemaVersion = 7,
            createdAtMs = -42L, // also exercise negative numbers
            label = "weird \"label\" with \\backslash and\nnewline\tand emoji 🔐",
            codecParams = mapOf(
                "key with spaces" to "value with \"quotes\"",
                "unicode" to "Ωμέγα",
            ),
        )
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, tricky, "x".toByteArray(), key)
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(tricky.appId, parsed.header.appId)
        assertEquals(tricky.schemaVersion, parsed.header.schemaVersion)
        assertEquals(tricky.createdAtMs, parsed.header.createdAtMs)
        assertEquals(tricky.label, parsed.header.label)
        assertEquals(tricky.codecParams, parsed.header.codecParams)
    }

    @Test
    fun emptyCodecParamsRoundTrip() {
        // The hand-rolled JSON has a special case for "empty object":
        // {} must parse correctly and round-trip back to an empty map.
        val h = sampleHeader().copy(codecParams = emptyMap())
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, h, "x".toByteArray(), key)
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(emptyMap<String, String>(), parsed.header.codecParams)
    }

    @Test
    fun emptyPayloadRoundTrips() {
        // Edge: a backup of an empty data set. The envelope must
        // accept zero-byte plaintext (and the codec's resulting
        // ciphertext) without confusing a zero-length payload for
        // EOF or truncation.
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), ByteArray(0), key)
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, BackupEnvelope.decryptPayload(parsed, codec, key).size)
    }

    @Test
    fun writeRejectsHeaderTooLargeForU16() {
        // Header length is encoded as u16 → max 65535 bytes. A header
        // beyond that can't fit on disk; write must fail loudly rather
        // than silently truncate or wrap.
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val giant = "x".repeat(70_000)
        val h = sampleHeader().copy(label = giant)
        try {
            BackupEnvelope.write(ByteArrayOutputStream(), codec, h, "p".toByteArray(), key)
            fail("oversize header must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun parseRejectsImplausiblePayloadLength() {
        // Construct an envelope whose payload-length field claims
        // 2 GiB. Real backups are kilobytes; the parser must refuse
        // to allocate a 2-GiB ByteArray on a corrupt or hostile
        // length field.
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), "x".toByteArray(), key)
        val bytes = out.toByteArray()
        // The u32 payload-length sits right after the u16 header-length
        // and the variable-length header bytes. We don't know the exact
        // offset without parsing, but: scan from the end for the last
        // 4-byte big-endian length field that could be the payload
        // length, or simpler — overwrite the last 4-byte segment
        // before the actual payload to claim a huge length.
        // The structure is: magic(4) + version(1) + codecId(1) +
        // headerLen(2) + header(N) + payloadLen(4) + payload. We
        // know payload size = bytes.length - (4+1+1+2+N+4). So the
        // payloadLen field is at offset 4+1+1+2+N = 8+N, where N is
        // the header length we can read from bytes[6..7].
        val headerLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val payloadLenOffset = 8 + headerLen
        // Write a value of 2 GiB - 1 as the payload-length (big-endian).
        val huge = (2 * 1024 * 1024 * 1024).toLong() - 1L
        bytes[payloadLenOffset]     = ((huge shr 24) and 0xFF).toByte()
        bytes[payloadLenOffset + 1] = ((huge shr 16) and 0xFF).toByte()
        bytes[payloadLenOffset + 2] = ((huge shr 8)  and 0xFF).toByte()
        bytes[payloadLenOffset + 3] = (huge          and 0xFF).toByte()
        try {
            BackupEnvelope.parse(ByteArrayInputStream(bytes))
            fail("implausible payload length must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun headerTamperBreaksAadBinding_evenWithMatchingCodec() {
        // The envelope passes the *raw* header bytes to the codec as
        // AAD on decrypt. If a tamperer flips a header byte and
        // updates the JSON to be still-parseable, the AAD bytes the
        // codec sees will differ from what was used at encrypt, and
        // a real authenticated codec will reject. Our FakeXor codec
        // doesn't authenticate, so this test verifies the headerRaw
        // bytes change after tamper — not that decryption fails.
        // (The end-to-end "decryption fails on header tamper" assertion
        // belongs to the real-codec test; here we pin the wiring.)
        val codec = FakeXorCodec()
        val key = FakeXorCodec.Key(0x01)
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(out, codec, sampleHeader(), "x".toByteArray(), key)
        val bytes = out.toByteArray()
        // Flip one byte inside the header JSON region (bytes 8..8+headerLen-1).
        val headerLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        // Flip a non-structural byte — pick an offset somewhere
        // mid-string. We want the JSON to still be parseable; flipping
        // a byte inside a label value is the realistic tamper.
        // Find the substring `"label":"` and flip the next char.
        val headerJson = String(bytes, 8, headerLen, Charsets.UTF_8)
        val labelIdx = headerJson.indexOf("\"label\":\"")
        val flipOffset = 8 + labelIdx + "\"label\":\"".length
        bytes[flipOffset] = (bytes[flipOffset].toInt() xor 0x01).toByte()
        // Now parse again — the header bytes the codec sees will
        // differ from the original.
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(bytes))
        // Re-create the original header bytes for comparison.
        val origHeaderRaw = run {
            val o = ByteArrayOutputStream()
            BackupEnvelope.write(o, codec, sampleHeader(), "x".toByteArray(), key)
            BackupEnvelope.parse(ByteArrayInputStream(o.toByteArray())).headerRaw
        }
        assertTrue(
            "tampered header bytes must differ from original",
            !parsed.headerRaw.contentEquals(origHeaderRaw),
        )
    }
}
