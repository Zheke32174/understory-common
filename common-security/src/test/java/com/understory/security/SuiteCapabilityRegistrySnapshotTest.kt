package com.understory.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteCapabilityRegistrySnapshotTest {

    @Test
    fun recognizedCapabilityEmptyPeerIsNotReportedAsUnknownVersion() {
        val browser = PeerInfo(
            packageName = "com.understory.browser",
            attestedVersion = 1,
            versionRecognized = true,
            capabilities = emptySet(),
            certVerified = true,
        )

        val snapshot = SuiteCapabilityRegistry.Snapshot(
            ownPackage = "com.understory.passgen",
            peers = listOf(browser),
        )

        assertTrue(snapshot.unknownVersionPeers.isEmpty())
        assertTrue(snapshot.capabilities.isEmpty())
        assertEquals(SuiteTier.PAIR, snapshot.tier)
    }

    @Test
    fun certVerifiedUnrecognizedVersionIsReportedUnknownEvenWhenInert() {
        val futurePeer = PeerInfo(
            packageName = "com.understory.browser",
            attestedVersion = 999,
            versionRecognized = false,
            capabilities = emptySet(),
            certVerified = true,
        )

        val snapshot = SuiteCapabilityRegistry.Snapshot(
            ownPackage = "com.understory.passgen",
            peers = listOf(futurePeer),
        )

        assertEquals(listOf("com.understory.browser"), snapshot.unknownVersionPeers)
        assertTrue(snapshot.capabilities.isEmpty())
    }
}
