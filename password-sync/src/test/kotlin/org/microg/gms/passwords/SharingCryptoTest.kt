/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class SharingCryptoTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rfc9180AuthenticatedX25519ChaChaVectorDecryptsExactly() {
        // CFRG published test-vectors.json: mode=2, kem=32, kdf=1, aead=3, sequence=0.
        val privateKey = hex("3ca22a6d1cda1bb9480949ec5329d3bf0b080ca4c45879c95eddb55c70b80b82")
        val sender = hex("f0f4f9e96c54aeed3f323de8534fffd7e0577e4ce269896716bcb95643c8712b")
        val enc = hex("f7674cc8cd7baa5872d1f33dbaffe3314239f6197ddf5ded1746760bfc847e0e")
        val ciphertext = hex("ab1a13c9d4f01a87ec3440dbd756e2677bd2ecf9df0ce7ed73869b98e00c09be111cb9fdf077347aeb88e61bdf")
        val info = hex("4f6465206f6e2061204772656369616e2055726e")
        val aad = hex("436f756e742d30")
        assertArrayEquals(hex("4265617574792069732074727574682c20747275746820626561757479"),
            SharingCrypto.openContext(enc + ciphertext, privateKey, sender, info, aad))
        assertArrayEquals(hex("1a478716d63cb2e16786ee93004486dc151e988b34b475043d3e0175bdb01c44"), SharingCrypto.publicKey(privateKey))
    }

    @Test fun emptyContextGoogleEnvelopeAuthenticatesTheSenderCiphertextAndRecipient() {
        val sender = ByteArray(32) { (it + 1).toByte() }; val recipient = ByteArray(32) { (it + 33).toByte() }
        val plaintext = "synthetic-only invitation".toByteArray()
        val ciphertext = SharingCrypto.seal(plaintext, sender, SharingCrypto.publicKey(recipient))
        assertEquals(plaintext.size + 48, ciphertext.size)
        assertArrayEquals(plaintext, SharingCrypto.open(ciphertext, recipient, SharingCrypto.publicKey(sender)))
        val wrong = ByteArray(32) { (it + 66).toByte() }
        assertThrows(ProtocolException::class.java) { SharingCrypto.open(ciphertext, recipient, SharingCrypto.publicKey(wrong)) }
        assertThrows(ProtocolException::class.java) { SharingCrypto.open(ciphertext, wrong, SharingCrypto.publicKey(sender)) }
        for (offset in listOf(0, 31, 32, ciphertext.lastIndex)) {
            val tampered = ciphertext.copyOf(); tampered[offset] = (tampered[offset].toInt() xor 1).toByte()
            assertThrows(ProtocolException::class.java) { SharingCrypto.open(tampered, recipient, SharingCrypto.publicKey(sender)) }
        }
    }

    @Test fun sharingPrivateKeysComeOnlyFromTheDecryptedCommittedNigoriBagAndRetainVersions() {
        val wrapping = NigoriKey(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val privateKey = ByteArray(32) { (it + 1).toByte() }
        val bag = Proto.field(2, wrapping.export()) + Proto.field(3, Proto.integer(1, 4) + Proto.field(2, privateKey)) +
            Proto.field(3, Proto.integer(1, 2) + Proto.field(2, ByteArray(32) { 3 }))
        val metadata = Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, wrapping.encrypt(bag)) + Proto.integer(2, 4))
        val keys = VaultKeys.unlock(metadata, customKey = wrapping.export())
        try {
            val sharing = PasswordSharingKeys.read(metadata, keys)
            try { assertEquals(4L, sharing.latest().version); assertEquals(2L, sharing.version(2).version)
                assertThrows(VaultLockedException::class.java) { sharing.version(9) }
            } finally { sharing.destroy() }
        } finally { keys.destroy(); wrapping.destroy() }
    }

    @Test fun malformedKeyLengthsAndCiphertextsNeverEnterTheCryptoProvider() {
        assertThrows(ProtocolException::class.java) { SharingCrypto.publicKey(ByteArray(31)) }
        assertThrows(ProtocolException::class.java) { SharingCrypto.open(ByteArray(47), ByteArray(32), ByteArray(32)) }
        assertThrows(ProtocolException::class.java) { SharingCrypto.seal(ByteArray(512 * 1024 + 1), ByteArray(32), ByteArray(32)) }
    }
}
