/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okio.ByteString.Companion.encodeUtf8
import org.microg.gms.migration.dct.*
import org.microg.gms.migration.security.DctIntegrity

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctHandshakeTest {
    private class Peer(val skip: Boolean = false, val attestation: DeviceIntegrityAttestation = DeviceIntegrityAttestation(status = 1, apple = AppleIntegrity())) : DctHandshake.Transport {
        val sent = mutableListOf<DctControlFrame>()
        var ack = true
        override fun exchange(frame: DctControlFrame, expectedField: Int): DctControlFrame {
            sent.add(frame)
            return when (expectedField) {
                2 -> DctControlFrame(device_info_response = DeviceInfoResponse(device_model = "Test phone", platform = 2))
                12 -> DctControlFrame(integrity_start_response = if (skip) DeviceIntegrityStartResponse(skip = IntegritySkip())
                    else DeviceIntegrityStartResponse(challenge = IntegrityChallenge(cloud_project_id = "123", salt = "peer-salt".encodeUtf8())))
                14 -> if (ack) DctControlFrame(integrity_attestation_ack = DeviceIntegrityAttestationAck()) else throw DctFailure(8018)
                else -> error("Unexpected request")
            }
        }
        override fun await(expectedField: Int) = if (expectedField == 13) DctControlFrame(integrity_attestation = attestation)
            else if (ack) DctControlFrame(integrity_attestation_ack = DeviceIntegrityAttestationAck()) else throw DctFailure(8018)
        override fun send(frame: DctControlFrame) { sent.add(frame) }
        override fun capabilities(request: DeviceCapabilityRequest): DeviceCapabilityResponse {
            assertFalse(request.field1!!.host_support)
            return DeviceCapabilityResponse(field1 = NcmCapabilities())
        }
    }
    @Test fun challengeUsesExactHashAndProjectAndRequiresAck() {
        val peer = Peer()
        var tokens = 0
        DctHandshake(peer, "Cyclon", "Synthetic") { project, hash ->
            tokens++; assertEquals(123L, project)
            assertEquals(DctIntegrity.requestHash("peer-salt".toByteArray(), "Cyclon", "Synthetic"), hash)
            "synthetic-provider-result"
        }.run("local-salt")
        assertEquals(1, tokens)
        assertEquals("local-salt", peer.sent[1].integrity_start_request!!.challenge!!.salt.utf8())
        assertEquals(1, peer.sent[2].integrity_attestation!!.status)
        assertEquals("synthetic-provider-result", peer.sent[2].integrity_attestation!!.google!!.token.utf8())
        assertNotNull(peer.sent.last().integrity_attestation_ack)
    }
    @Test fun peerSelectedSkipDoesNotCallProviderOrFabricateAttestation() {
        val peer = Peer(skip = true)
        DctHandshake(peer, "Cyclon", "Synthetic") { _, _ -> error("Must not request token") }.run("salt")
        assertTrue(peer.sent.none { it.integrity_attestation != null })
        assertNotNull(peer.sent.last().integrity_attestation_ack)
    }
    @Test fun tokenFailureAndMissingAckAreAttestationFailures() {
        val failed = assertThrows(DctFailure::class.java) {
            DctHandshake(Peer(), "Cyclon", "Synthetic") { _, _ -> throw IllegalStateException("Provider denied") }.run("salt")
        }
        assertEquals(8018, failed.status)
        val peer = Peer().also { it.ack = false }
        assertEquals(8018, assertThrows(DctFailure::class.java) {
            DctHandshake(peer, "Cyclon", "Synthetic") { _, _ -> "synthetic" }.run("salt")
        }.status)
    }
    @Test fun unsuccessfulOrWrongPlatformAttestationIsRejectedBeforeAck() {
        listOf(DeviceIntegrityAttestation(status = 0, apple = AppleIntegrity()),
            DeviceIntegrityAttestation(status = 1, google = GoogleIntegrity(token = "invalid-for-ios".encodeUtf8()))).forEach {
            val peer = Peer(skip = true, attestation = it)
            assertEquals(8018, assertThrows(DctFailure::class.java) {
                DctHandshake(peer, "Cyclon", "Synthetic") { _, _ -> error("skip") }.run("salt")
            }.status)
            assertTrue(peer.sent.none { it.integrity_attestation_ack != null })
        }
    }
    @Test fun wireBodiesUseOfflineFrameOnlyForControls() {
        val frame = DctOfflineFrame(control = DctControlFrame(integrity_attestation_ack = DeviceIntegrityAttestationAck()))
        assertArrayEquals(byteArrayOf(0x0a, 0x02, 0x72, 0x00), frame.encode())
        assertArrayEquals(byteArrayOf(0x0a, 0x00), DeviceCapabilityRequest(field1 = NcmCapabilities()).encode())
        assertEquals(frame, DctOfflineFrame.ADAPTER.decode(frame.encode()))
    }
}
