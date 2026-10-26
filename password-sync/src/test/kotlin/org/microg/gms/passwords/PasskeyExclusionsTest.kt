/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasskeyExclusionsTest {
    private fun credential(rp: String, id: Int, user: Int = 1): SyncedPasskey = SyncedPasskey.decode(
        Proto.field(1, ByteArray(16) { id.toByte() }) + Proto.field(2, byteArrayOf(id.toByte())) +
            Proto.field(3, rp) + Proto.field(4, byteArrayOf(user.toByte())) + Proto.field(12, ByteArray(28)))

    @Test fun aCredentialIdFromAnotherRpCannotBlockOrRevealThatRegistration() {
        val excluded = listOf(byteArrayOf(7))
        assertFalse(PasskeyExclusions.contains("target.invalid", excluded, emptyList()))
        assertFalse(PasskeyExclusions.contains("target.invalid", excluded, listOf(credential("other.invalid", 7))))
        assertFalse(PasskeyExclusions.contains("target.invalid", excluded, listOf(credential("other.invalid", 7), credential("target.invalid", 8))))
    }

    @Test fun aMatchingCredentialAtTheRequestedRpRemainsExcludedRegardlessOfUser() {
        val excluded = listOf(byteArrayOf(7))
        assertTrue(PasskeyExclusions.contains("target.invalid", excluded, listOf(credential("target.invalid", 7))))
        assertTrue(PasskeyExclusions.contains("target.invalid", excluded, listOf(credential("target.invalid", 7, user = 2))))
        assertTrue(PasskeyExclusions.contains("target.invalid", excluded, listOf(credential("other.invalid", 7), credential("target.invalid", 7))))
        assertFalse(PasskeyExclusions.contains("target.invalid", emptyList(), listOf(credential("target.invalid", 7))))
    }
}
