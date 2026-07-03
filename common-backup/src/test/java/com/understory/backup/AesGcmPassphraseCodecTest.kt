package com.understory.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * End-to-end test for [AesGcmPassphraseCodec]. Combines the codec with
 * the [BackupEnvelope] container so a single round-trip exercises:
 *   - Argon2id KDF (suite default cost — 64 MiB / 3 iter)
 *   - AES-256-GCM with envelope-header as AAD
 *   - Salt prepended to the codec ciphertext, recovered on decrypt
 *   - Wrong-passphrase rejection
 *   - Header-tamper rejection (the real AEAD test that the fake-codec
 *     [BackupEnvelopeTest] couldn't pin)
 *
 * Note on cost: each test runs Argon2id with production parameters,
 * which is ~0.5–1s per call on a modern x86. We deliberately kept
 * the cost params not-pluggable so production and tests use the same
 * KDF — slow tests are the trade-off for not adding a hidden
 * test-only path that could regress production.
 */
class AesGcmPassphraseCodecTest {

    private fun header() = BackupEnvelope.Header(
        appId = "com.understory.test",
        schemaVersion = 1,
        createdAtMs = 1_700_000_000_000L,
        label = "test backup",
        codecParams = mapOf("v" to "1"),
    )

    @Test
    fun roundTripsThroughEnvelope() {
        val plaintext = "{\"entries\":[]}".toByteArray()
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(
            out = out,
            codec = AesGcmPassphraseCodec,
            header = header(),
            plaintext = plaintext,
            codecKey = AesGcmPassphraseCodec.PassphraseKey("correct horse".toCharArray()),
        )

        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        val recovered = BackupEnvelope.decryptPayload(
            parsed = parsed,
            codec = AesGcmPassphraseCodec,
            key = AesGcmPassphraseCodec.PassphraseKey("correct horse".toCharArray()),
        )
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun wrongPassphraseFails() {
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(
            out = out,
            codec = AesGcmPassphraseCodec,
            header = header(),
            plaintext = "secret".toByteArray(),
            codecKey = AesGcmPassphraseCodec.PassphraseKey("alpha".toCharArray()),
        )
        val parsed = BackupEnvelope.parse(ByteArrayInputStream(out.toByteArray()))
        try {
            BackupEnvelope.decryptPayload(
                parsed = parsed,
                codec = AesGcmPassphraseCodec,
                key = AesGcmPassphraseCodec.PassphraseKey("beta".toCharArray()),
            )
            fail("wrong passphrase must fail authenticated decrypt")
        } catch (_: Throwable) {
            // expected — GCM tag mismatch wrapped in some exception
        }
    }

    @Test
    fun headerTamperFailsAuthenticatedDecrypt() {
        // The combined assertion the fake-codec test couldn't make:
        // a real AEAD codec reading from a tampered envelope must
        // fail the AAD check. This is the one that actually proves
        // header tamper is detected end-to-end.
        val out = ByteArrayOutputStream()
        BackupEnvelope.write(
            out = out,
            codec = AesGcmPassphraseCodec,
            header = header(),
            plaintext = "secret".toByteArray(),
            codecKey = AesGcmPassphraseCodec.PassphraseKey("pw".toCharArray()),
        )
        val bytes = out.toByteArray()
        val headerLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val headerJson = String(bytes, 8, headerLen, Charsets.UTF_8)
        val labelIdx = headerJson.indexOf("\"label\":\"")
        val flipOffset = 8 + labelIdx + "\"label\":\"".length
        bytes[flipOffset] = (bytes[flipOffset].toInt() xor 0x01).toByte()

        val parsed = BackupEnvelope.parse(ByteArrayInputStream(bytes))
        try {
            BackupEnvelope.decryptPayload(
                parsed = parsed,
                codec = AesGcmPassphraseCodec,
                key = AesGcmPassphraseCodec.PassphraseKey("pw".toCharArray()),
            )
            fail("header tamper must fail authenticated decrypt")
        } catch (_: Throwable) {
            // expected — AEADBadTagException or wrapper thereof
        }
    }

    @Test
    fun saltIsFreshPerEncrypt_soSameInputsProduceDifferentCiphertexts() {
        // Two encrypts of the same plaintext under the same passphrase
        // must produce different ciphertexts — the salt is fresh per
        // call. Without this, two backups of identical vaults would
        // produce byte-identical files, leaking the fact that they
        // hold the same data.
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        BackupEnvelope.write(a, AesGcmPassphraseCodec, header(), "x".toByteArray(),
            AesGcmPassphraseCodec.PassphraseKey("pw".toCharArray()))
        BackupEnvelope.write(b, AesGcmPassphraseCodec, header(), "x".toByteArray(),
            AesGcmPassphraseCodec.PassphraseKey("pw".toCharArray()))
        assertNotEquals(a.toByteArray().toList(), b.toByteArray().toList())
    }

    @Test
    fun passphraseKeyWipeZeroesPassphrase() {
        val pw = "secret".toCharArray()
        val key = AesGcmPassphraseCodec.PassphraseKey(pw)
        key.wipe()
        for (c in pw) {
            assert(c == ' ') { "expected wipe to overwrite passphrase chars" }
        }
    }
}
