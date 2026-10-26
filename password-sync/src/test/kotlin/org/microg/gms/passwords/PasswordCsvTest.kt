/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordCsvTest {
    @Test fun googleColumnsRoundTripExactWhitespaceQuotesUnicodeNewlinesAndFormulaLikeSecrets() {
        val data = PasswordData.create("https://fixture.invalid/", "https://fixture.invalid/login", " =fixture,\"é\"\nuser ", "=fixture\r\npassword,\" ", 1234L)
            .withDisplayName("Fixture, \"name\"").withDefaultNote("first\r\nsecond,\"é\"", 1234L)
        val encoded = PasswordCsv.encode(listOf(data))
        val restored = PasswordCsv.decode(encoded, 2345L).single()
        assertEquals(data.username, restored.username); assertEquals(data.password, restored.password)
        assertEquals(data.defaultNote!!.value, restored.defaultNote!!.value); assertEquals(data.origin, restored.origin)
        assertEquals(data.displayName, restored.displayName); assertEquals(data.tag(), restored.tag())
        assertTrue(String(encoded, Charsets.UTF_8).startsWith("\"name\",\"url\",\"username\",\"password\",\"note\"\r\n"))
    }
    @Test fun malformedCsvDuplicateColumnsDuplicateIdentitiesAndInvalidUtf8AreRefusedBeforeImport() {
        val header = "name,url,username,password,note\r\n"
        for (text in listOf(header + "a,https://fixture.invalid/,fixture,\"unterminated,note",
            header + "a,https://fixture.invalid/,fixture,\"closed\"bad,note",
            header + "a,https://fixture.invalid/,fixture,password,note,extra",
            "url,username,password,password\nhttps://fixture.invalid/,fixture,one,two")) {
            assertThrows(ProtocolException::class.java) { PasswordCsv.decode(text.toByteArray(), 1234L) }
        }
        val row = "a,https://fixture.invalid/,fixture,password,note\r\n"
        assertThrows(ConflictException::class.java) { PasswordCsv.decode((header + row + row).toByteArray(), 1234L) }
        assertThrows(ProtocolException::class.java) { PasswordCsv.decode(byteArrayOf(0xc3.toByte(), 0x28), 1234L) }
    }
    @Test fun optionalGoogleNameAndNoteColumnsAndBomAreAcceptedWithoutAlteringSecrets() {
        val imported = PasswordCsv.decode("\uFEFFurl,username,password\nhttps://fixture.invalid/,fixture,  fixture secret  \n".toByteArray(), 1234L).single()
        assertEquals("  fixture secret  ", imported.password); assertEquals("", imported.displayName); assertTrue(imported.notes.isEmpty())
    }
    @Test fun androidNativeCredentialsKeepTheirCertificateFacetOriginAndIdentity() {
        val facet = "android://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=@app.fixture/"
        for (origin in listOf(facet, facet.removeSuffix("/"))) {
            val data = PasswordData.create(origin, origin, "fixture", "fixture secret", 1234L)
            val restored = PasswordCsv.decode(PasswordCsv.encode(listOf(data)), 2345L).single()
            assertEquals(data.realm, restored.realm); assertEquals(data.origin, restored.origin)
            assertEquals(data.tag(), restored.tag()); assertEquals(data.password, restored.password)
        }
    }
}
