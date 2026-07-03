package com.understory.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Round-trip and schema-split tests for [VaultRecoveryEnvelope] — the shared
 * export/import surface. Real Argon2id + AES-GCM (same cost note as
 * [AesGcmPassphraseCodecTest]); the split-key vs legacy routing is pure logic.
 */
class VaultRecoveryEnvelopeTest {

    private val appId = "com.understory.passgen"
    private val schema = VaultRecoveryEnvelope.SchemaVersions.SPLIT_KEY_MIN

    @Test
    fun writeThenOpenAndDecrypt_roundTrips() {
        val payload = "secret vault contents".toByteArray()
        val key = "correct-recovery-key".toCharArray()

        val bytes = ByteArrayOutputStream().use { out ->
            VaultRecoveryEnvelope.writeEncrypted(
                out, appId, schema, payload.copyOf(), key,
                label = "my label", nowMs = 123L,
            )
            out.toByteArray()
        }

        val opened = VaultRecoveryEnvelope.open(ByteArrayInputStream(bytes))
        assertEquals(appId, opened.appId)
        assertEquals(schema, opened.schemaVersion)
        assertFalse(opened.isLegacy)
        assertEquals("my label", opened.header.label)
        assertEquals(123L, opened.header.createdAtMs)

        val decrypted = VaultRecoveryEnvelope.decrypt(opened, key)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun wrongKey_failsDecryption() {
        val bytes = ByteArrayOutputStream().use { out ->
            VaultRecoveryEnvelope.writeEncrypted(
                out, appId, schema, "data".toByteArray(), "right".toCharArray(),
            )
            out.toByteArray()
        }
        val opened = VaultRecoveryEnvelope.open(ByteArrayInputStream(bytes))
        try {
            VaultRecoveryEnvelope.decrypt(opened, "wrong".toCharArray())
            fail("wrong key must not decrypt")
        } catch (expected: Exception) {
            // GCM auth failure — mapped to IMPORT_WRONG_KEY by the UI.
        }
    }

    @Test
    fun open_readsAppIdWithoutDecrypting() {
        val bytes = ByteArrayOutputStream().use { out ->
            VaultRecoveryEnvelope.writeEncrypted(
                out, "com.understory.aegis", schema, "x".toByteArray(), "k".toCharArray(),
            )
            out.toByteArray()
        }
        // No key supplied; open() is parse-only, so app-mismatch is catchable
        // before prompting for the recovery key.
        val opened = VaultRecoveryEnvelope.open(ByteArrayInputStream(bytes))
        assertEquals("com.understory.aegis", opened.appId)
    }

    @Test
    fun schemaVersions_routeLegacyBelowSplitMin() {
        assertTrue(VaultRecoveryEnvelope.SchemaVersions.isLegacy(1))
        assertFalse(VaultRecoveryEnvelope.SchemaVersions.isLegacy(
            VaultRecoveryEnvelope.SchemaVersions.SPLIT_KEY_MIN))
        assertFalse(VaultRecoveryEnvelope.SchemaVersions.isLegacy(99))
    }

    @Test
    fun legacySchemaFile_opensWithLegacyFlagSet() {
        // A pre-v2 file (schemaVersion 1) round-trips and is flagged legacy so
        // the app takes its recovery-key==KEK import branch (backups D-4 compat).
        val bytes = ByteArrayOutputStream().use { out ->
            VaultRecoveryEnvelope.writeEncrypted(
                out, "com.understory.backups", schemaVersion = 1,
                "legacy".toByteArray(), "kek-as-recovery".toCharArray(),
            )
            out.toByteArray()
        }
        val opened = VaultRecoveryEnvelope.open(ByteArrayInputStream(bytes))
        assertTrue(opened.isLegacy)
        assertArrayEquals(
            "legacy".toByteArray(),
            VaultRecoveryEnvelope.decrypt(opened, "kek-as-recovery".toCharArray()),
        )
    }
}
