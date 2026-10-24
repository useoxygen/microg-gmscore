/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import org.microg.gms.migration.dct.*
import okio.ByteString.Companion.encodeUtf8
import org.microg.gms.migration.security.DctIntegrity

internal class DctFailure(val status: Int) : Exception("DCT failed: $status")

/** Protocol policy independent of Android radios, HTTP and Binder. No mock attestation branch. */
internal class DctHandshake(private val transport: Transport, private val manufacturer: String, private val model: String,
                            private val stage: (String) -> Unit = {}, private val token: (Long, String) -> String) {
    interface Transport {
        fun exchange(frame: DctControlFrame, expectedField: Int): DctControlFrame
        fun await(expectedField: Int): DctControlFrame
        fun send(frame: DctControlFrame)
        fun capabilities(request: DeviceCapabilityRequest): DeviceCapabilityResponse
    }
    fun run(salt: String): DeviceInfoResponse {
        val peer = transport.exchange(DctControlFrame(device_info_request = DeviceInfoRequest(
            device_model = model, platform = 1, manufacturer = manufacturer)), 2).device_info_response ?: throw DctFailure(8017)
        if (peer.platform != 2) throw DctFailure(8017) // this receiver implements the iPhone import path only
        if (transport.capabilities(DeviceCapabilityRequest(field1 = NcmCapabilities())).field1 == null) throw DctFailure(8017)
        stage("Integrity challenge sent")
        val response = transport.exchange(DctControlFrame(integrity_start_request = DeviceIntegrityStartRequest(
            challenge = IntegrityChallenge(salt = salt.encodeUtf8()))), 12).integrity_start_response
            ?: throw DctFailure(8018)
        val local = when {
            response.challenge != null -> {
                val challenge = requireNotNull(response.challenge)
                val project = challenge.cloud_project_id.toLongOrNull() ?: 58585360821L
                if (project <= 0 || challenge.salt.size == 0 || challenge.salt.size > 4096) throw DctFailure(8018)
                stage("Integrity peer requested token")
                val value = try { token(project, DctIntegrity.requestHash(challenge.salt.toByteArray(), manufacturer, model)) }
                    catch (e: Exception) { throw DctFailure(8018) }
                if (value.isEmpty()) throw DctFailure(8018)
                DeviceIntegrityAttestation(status = 1, google = GoogleIntegrity(token = value.encodeUtf8()))
            }
            response.skip != null -> { stage("Integrity peer selected skip"); null } // a peer-selected skip; never choose this to hide a token failure
            else -> throw DctFailure(8018)
        }
        if (local != null) { transport.send(DctControlFrame(integrity_attestation = local)); stage("Integrity token sent; awaiting peer attestation and ACK") }
        val remote = transport.await(13).integrity_attestation ?: throw DctFailure(8018)
        if (remote.status != 1 || remote.apple == null) throw DctFailure(8018)
        stage("Integrity Apple attestation received")
        transport.send(DctControlFrame(integrity_attestation_ack = DeviceIntegrityAttestationAck()))
        if (local != null && transport.await(14).integrity_attestation_ack == null) throw DctFailure(8018)
        stage("Integrity exchange complete")
        return peer
    }
}
