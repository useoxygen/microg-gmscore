/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharingProtocolTest {
    private val parent = "users/me/birthdays/fixture"
    private val senderPrivate = ByteArray(32) { (it + 1).toByte() }
    private val receiverPrivate = ByteArray(32) { (it + 33).toByte() }
    private fun recipient(id: String, key: ByteArray, version: Long = 7): ByteArray = Proto.field(1, id) +
        Proto.field(2, Proto.field(1, "fixture@example.invalid") + Proto.field(2, "Fixture sender")) +
        Proto.field(3, Proto.integer(1, version) + Proto.field(2, SharingCrypto.publicKey(key)))
    private fun password() = PasswordData.create("https://example.invalid/", "https://example.invalid/login", "synthetic", "fixture-password", 1000)

    @Test fun invitationIsAuthenticatedBeforeImportAndRetainsSenderProvenance() {
        val wrapping = NigoriKey(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val bag = Proto.field(2, wrapping.export()) + Proto.field(3, Proto.integer(1, 7) + Proto.field(2, receiverPrivate))
        val metadata = Proto.field(1, parent) + Proto.field(3, Proto.field(1, wrapping.encrypt(bag)) + Proto.integer(2, 4))
        val keys = VaultKeys.unlock(metadata, customKey = wrapping.export())
        val sharing = PasswordSharingKeys.read(metadata, keys)
        try {
            val plaintext = SharingPasswordGroup.encode(password())
            val specifics = Proto.field(1, UUID.randomUUID().toString()) + Proto.field(2, recipient("sender", senderPrivate)) +
                Proto.field(3, SharingCrypto.seal(plaintext, senderPrivate, SharingCrypto.publicKey(receiverPrivate))) + Proto.integer(6, 7)
            val resource = Proto.field(1, "$parent/incomingInvitations/fixture") + Proto.field(5, specifics)
            val decoded = SharingInvitation.decode(resource, parent).passwords(sharing, 2000).single()
            assertEquals(password().password, decoded.password); assertEquals(password().username, decoded.username)
            val proto = Proto.decode(decoded.encode())
            assertEquals(5L, proto.integer(13)); assertEquals("fixture@example.invalid", proto.string(23))
            assertEquals("Fixture sender", proto.string(24)); assertNotNull(proto.integer(25)); assertEquals(0L, proto.integer(26))
            assertThrows(ProtocolException::class.java) { SharingInvitation.decode(Proto.field(1, "$parent/incomingInvitations/fixture") +
                Proto.field(5, specifics + Proto.field(4, plaintext)), parent) }
            assertThrows(ProtocolException::class.java) { SharingInvitation.decode(resource, "users/me/birthdays/other") }
        } finally { sharing.destroy(); keys.destroy(); wrapping.destroy() }
    }

    @Test fun groupingCannotAlterTheRecipientKeyOrLeakClientOnlyPlaintextIntoAnOutgoingRequest() {
        val listed = SharingRecipient.decode(recipient("recipient", receiverPrivate))
        val rotated = SharingRecipient.decode(recipient("recipient", senderPrivate, 8))
        assertFalse(listed.sameKey(rotated))
        var sawRequest = false
        val api = PasswordSharingApi(PasswordTransport { method, request ->
            assertEquals(PasswordMethod.CREATE_OUTGOING_SHARING, method)
            val proto = Proto.decode(request); assertEquals(parent, proto.string(1)); assertArrayEquals(byteArrayOf(9), proto.one(3))
            val invitation = Proto.decode(Proto.decode(proto.one(4)).one(1))
            assertNull(invitation.optional(4)); assertEquals(7L, invitation.integer(6)); assertEquals(3L, invitation.integer(7))
            assertFalse(String(request, Charsets.ISO_8859_1).contains(password().password))
            sawRequest = true; byteArrayOf()
        })
        val payload = SharingPasswordGroup.encode(password())
        val invitation = Proto.field(1, UUID.randomUUID().toString()) + Proto.field(2, listed.userId) +
            Proto.field(3, SharingCrypto.seal(payload, senderPrivate, listed.publicKey())) + Proto.integer(6, listed.version) + Proto.integer(7, 3)
        api.send(api.outgoingRequest(parent, invitation, byteArrayOf(9))); assertTrue(sawRequest)
    }

    @Test fun malformedOrMismatchedStoredAuthoritiesCannotBecomeSharedFills() {
        val sender = SharingRecipient.decode(recipient("sender", senderPrivate))
        val mismatched = Proto.field(2, Proto.field(1, "fixture") + Proto.field(2, "secret") + Proto.field(3,
            Proto.integer(2, 0) + Proto.field(3, "https://example.invalid/") + Proto.field(4, "https://other.invalid/login")))
        assertThrows(ProtocolException::class.java) { SharingPasswordGroup.decode(mismatched, sender, 1000) }
        assertThrows(ProtocolException::class.java) { SharingPasswordGroup.encode(password().withBlocked(true)) }
        assertThrows(ProtocolException::class.java) { SharingRecipient.decode(recipient("", senderPrivate)) }
        assertThrows(ProtocolException::class.java) { SharingRecipient.decode(Proto.field(1, "fixture") + Proto.field(3,
            Proto.integer(1, 1) + Proto.field(2, ByteArray(32)))) }
    }

    @Test fun recipientAndIncomingListingsAreBoundedAndDoNotImplyAcceptance() {
        val raw = recipient("fixture", senderPrivate)
        val api = PasswordSharingApi(PasswordTransport { method, request ->
            when (method) {
                PasswordMethod.LIST_SHARING_RECIPIENTS -> { assertTrue(request.isEmpty()); Proto.field(1, raw) + Proto.field(1, raw) }
                else -> error("Unexpected method")
            }
        })
        assertThrows(ProtocolException::class.java) { api.recipients() }
    }

    @Test fun aFamilyMemberWithoutARegisteredKeyRemainsIneligibleWithoutHidingEligibleMembers() {
        val unenrolled = Proto.field(1, "unenrolled") + Proto.field(2, Proto.field(1, "fixture@example.invalid"))
        val api = PasswordSharingApi(PasswordTransport { _, _ -> Proto.field(1, unenrolled) + Proto.field(1, recipient("enrolled", senderPrivate)) })
        val values = api.recipients()
        assertEquals(2, values.size); assertFalse(values[0].eligible); assertTrue(values[1].eligible)
        assertThrows(VaultLockedException::class.java) { values[0].publicKey() }
        assertFalse(values[0].sameKey(values[0]))
    }
}
