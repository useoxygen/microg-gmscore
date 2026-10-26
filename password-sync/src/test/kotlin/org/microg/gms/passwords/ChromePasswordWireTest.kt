/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class ChromePasswordWireTest {
    private fun password() = PasswordData.create("https://example.invalid/", "https://example.invalid/", "fixture-user", "fixture-password", 1234)

    @Test fun chromeDeleteAcceptsBareSpecificsAndPreservesItsIdentityAndUnknownFields() {
        // Chrome 145 removeLogin sends PasswordSpecificsData; pinned bexa passes it straight to bfgl.g.
        val bare = password().encode() + Proto.field(700, byteArrayOf(9, 8, 7))
        val internal = ChromePasswordWire.deleteInput(bare)
        val decoded = PasswordData.decode(Proto.decode(internal).one(1))
        assertEquals(password().tag(), decoded.tag())
        assertArrayEquals(bare, decoded.encode())
        assertArrayEquals(byteArrayOf(9, 8, 7), Proto.decode(decoded.encode()).one(700))
    }

    @Test fun deleteRejectsTheSaveWrapperRatherThanConfusingTwoWireContracts() {
        val saveWrapper = Proto.field(1, password().encode()) + Proto.field(3, Proto.field(1, byteArrayOf(1)))
        assertThrows(ProtocolException::class.java) { ChromePasswordWire.deleteInput(saveWrapper) }
    }
}
