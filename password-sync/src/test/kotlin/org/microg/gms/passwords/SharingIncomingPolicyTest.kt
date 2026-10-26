/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class SharingIncomingPolicyTest {
    private val privateKey = ByteArray(32) { (it + 1).toByte() }
    private fun sender(name: String = "Fixture sender") = SharingRecipient.decode(Proto.field(1, "fixture") +
        Proto.field(2, Proto.field(1, "fixture@example.invalid") + Proto.field(2, name) + Proto.field(3, "https://images.invalid/avatar")) +
        Proto.field(3, Proto.integer(1, 7) + Proto.field(2, SharingCrypto.publicKey(privateKey))))
    private fun password() = PasswordData.create("https://example.invalid/", "https://example.invalid/login", "fixture", "secret", 1000)
    private fun changed(data: PasswordData, field: Int, value: String) = PasswordData.decode(Proto.decode(data.encode()).replace(field, value.toByteArray()).encode())
    private fun element(origin: String = "https://example.invalid/login", scheme: Long = 0): ByteArray = Proto.integer(2, scheme) +
        Proto.field(3, "https://example.invalid/") + Proto.field(4, origin) + Proto.field(5, "login") + Proto.field(7, "pass") +
        Proto.field(8, "Fixture site") + Proto.field(9, "https://images.invalid/site")
    private fun group(elements: List<ByteArray> = listOf(element()), username: String = "fixture", secret: String = "secret") =
        Proto.field(2, elements.fold(Proto.field(1, username) + Proto.field(2, secret)) { out, value -> out + Proto.field(3, value) })

    @Test fun identityUsesExactlyTheFiveGmsFieldsAndIgnoresSchemeAndBranding() {
        val original = password()
        for (field in listOf(6, 3, 2, 5, 7)) assertFalse(SharingIncomingPolicy.sameIdentity(original, changed(original, field, "different")))
        for (field in listOf(8, 15, 23, 24, 27)) assertTrue(SharingIncomingPolicy.sameIdentity(original, changed(original, field, "different")))
        val differentScheme = PasswordData.decode(Proto.decode(original.encode()).replaceInteger(1, 99).encode())
        assertTrue(SharingIncomingPolicy.sameIdentity(original, differentScheme))
        assertThrows(ConflictException::class.java) { SharingIncomingPolicy.classify(original, changed(original, 3, "https://other.invalid/")) }
    }

    @Test fun duplicateAndEveryUpdatePartitionPreserveTheExistingCredentialByDefault() {
        val incoming = PasswordData.decode(Proto.decode(password().encode()).replaceInteger(13, 5).replace(23, "sender@example.invalid".toByteArray()).encode())
        assertEquals(SharingIncomingDisposition.NEW, SharingIncomingPolicy.classify(incoming, null))
        assertEquals(SharingIncomingDisposition.DUPLICATE, SharingIncomingPolicy.classify(incoming, changed(incoming, 24, "Different metadata")))
        val differentPassword = incoming.withPassword("old", 2000)
        assertEquals(SharingIncomingDisposition.SHARED_SAME_SENDER_UPDATE, SharingIncomingPolicy.classify(incoming, differentPassword))
        assertEquals(SharingIncomingDisposition.SHARED_DIFFERENT_SENDER_UPDATE, SharingIncomingPolicy.classify(incoming, changed(differentPassword, 23, "other@example.invalid")))
        val manual = PasswordData.decode(Proto.decode(differentPassword.encode()).replaceInteger(13, 3).encode())
        assertEquals(SharingIncomingDisposition.NON_SHARED_UPDATE, SharingIncomingPolicy.classify(incoming, manual))
        assertFalse(SharingIncomingPolicy.AUTO_APPROVE_SAME_SENDER_UPDATES)
    }

    @Test fun validationCountsUtf16CodeUnitsAndPartitionsInvalidSiblings() {
        val exact = "\uD83D\uDE00".repeat(512)
        assertEquals(1024, exact.length)
        assertEquals(1, SharingPasswordGroup.decodeBatch(group(username = exact), sender(), 2000).passwords.size)
        val tooLong = SharingPasswordGroup.decodeBatch(group(username = exact + "x"), sender(), 2000)
        assertEquals(0, tooLong.passwords.size); assertEquals(1, tooLong.invalidCount)
        val mixed = SharingPasswordGroup.decodeBatch(group(listOf(element(), element(origin = ""))), sender(), 2000)
        assertEquals(1, mixed.passwords.size); assertEquals(1, mixed.invalidCount)
    }

    @Test fun eachGmsBoundedStringRejects1025WhileOriginHasNo1024Limit() {
        val long = "a".repeat(1025)
        for (field in listOf(3, 5, 7, 8)) {
            val malformedElement = Proto.decode(element()).replace(field, long.toByteArray()).encode()
            assertEquals(1, SharingPasswordGroup.decodeBatch(group(listOf(malformedElement)), sender(), 2000).invalidCount)
        }
        assertEquals(1, SharingPasswordGroup.decodeBatch(group(username = long), sender(), 2000).invalidCount)
        assertEquals(1, SharingPasswordGroup.decodeBatch(group(secret = long), sender(), 2000).invalidCount)
        assertEquals(1, SharingPasswordGroup.decodeBatch(group(), sender(long), 2000).invalidCount)
        assertEquals(1, SharingPasswordGroup.decodeBatch(group(listOf(element(origin = long))), sender(), 2000).passwords.size)
    }

    @Test fun senderAvatarAndAllReceivedMetadataSurviveWithoutGrantingOtherSchemesFillAuthority() {
        val data = SharingPasswordGroup.decodeBatch(group(listOf(element(scheme = 1))), sender(), 2000).passwords.single()
        val proto = Proto.decode(data.encode())
        assertEquals(1L, proto.integer(1)); assertEquals(5L, proto.integer(13))
        assertEquals("fixture@example.invalid", proto.string(23)); assertEquals("Fixture sender", proto.string(24))
        assertEquals((2000L + 11_644_473_600_000L) * 1000, proto.integer(11))
        assertEquals(proto.integer(11), proto.integer(25)); assertEquals(0L, proto.integer(26))
        assertEquals("https://images.invalid/avatar", proto.string(27)); assertEquals("https://images.invalid/site", proto.string(16))
        assertFalse(data.canFillRealm(data.realm))
        val extra = PasswordData.decode(data.encode() + Proto.field(99, "future-field"))
        assertTrue(SharingIncomingPolicy.sameImportedPassword(data, extra))
        assertFalse(SharingIncomingPolicy.sameImportedPassword(data, changed(data, 27, "different")))
    }

    @Test fun malformedProtobufIsAFailureWhileStructurallyEmptyGroupsAreConsumedWithoutWrites() {
        assertThrows(ProtocolException::class.java) { SharingPasswordGroup.decodeBatch(group(listOf(byteArrayOf(0x80.toByte()))), sender(), 2000) }
        val empty = SharingPasswordGroup.decodeBatch(byteArrayOf(), sender(), 2000)
        assertTrue(empty.passwords.isEmpty()); assertEquals(0, empty.invalidCount)
    }
}
