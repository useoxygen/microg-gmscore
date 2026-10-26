/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.ListSecurityDomainMembersResponse
import org.microg.gms.auth.folsom.SecurityDomainMemberResponse
import org.microg.gms.auth.folsom.utils.FolsomMemberPages

class FolsomMemberPagesTest {
    private fun page(name: String, token: String = "") = ListSecurityDomainMembersResponse(
        members = listOf(SecurityDomainMemberResponse(name = "users/me/members/$name")), nextPageToken = token)
    @Test fun recoveryDeviceBeyondFirstPageIsRetainedAndPartialListsNeverReturn() {
        val tokens = ArrayList<String>()
        val result = FolsomMemberPages("owner", { "owner" }) { token ->
            tokens.add(token)
            if (token.isEmpty()) page("other-device", "second") else page("this-device")
        }.load()
        assertEquals(listOf("", "second"), tokens)
        assertEquals(listOf("users/me/members/other-device", "users/me/members/this-device"), result.members.map { it.name })
        assertThrows(IllegalStateException::class.java) { FolsomMemberPages("owner", { "owner" }) { token ->
            if (token.isEmpty()) page("first", "second") else error("fixture offline")
        }.load() }
    }
    @Test fun loopingPagesDuplicateMembersAndSameNameAccountReplacementAreRefused() {
        assertThrows(IllegalStateException::class.java) { FolsomMemberPages("owner", { "owner" }) { page("same", "loop") }.load() }
        var id = "owner"
        assertThrows(IllegalStateException::class.java) { FolsomMemberPages("owner", { id }) {
            id = "replacement"; page("old-owner-device")
        }.load() }
        var fetched = false
        assertThrows(IllegalStateException::class.java) { FolsomMemberPages("owner", { "replacement" }) {
            fetched = true; page("device")
        }.load() }
        assertFalse(fetched)
    }
}
