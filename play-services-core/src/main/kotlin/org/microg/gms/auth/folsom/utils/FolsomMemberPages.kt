/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.ListSecurityDomainMembersResponse
import org.microg.gms.auth.folsom.SecurityDomainMemberResponse

/** A partial member list cannot establish that a registered recovery device disappeared. */
internal class FolsomMemberPages(private val expectedId: String, private val identity: () -> String,
    private val fetch: (String) -> ListSecurityDomainMembersResponse) {
    fun load(): ListSecurityDomainMembersResponse {
        val members = ArrayList<SecurityDomainMemberResponse>()
        val names = HashSet<String>()
        val tokens = HashSet<String>()
        var token = ""
        do {
            check(identity() == expectedId) { "Recovery account changed" }
            val page = fetch(token)
            check(identity() == expectedId) { "Recovery account changed" }
            page.members.forEach { member ->
                val name = member.name ?: error("Missing recovery member identity")
                check(name.startsWith("users/me/members/") && name.length > 17 && names.add(name)) { "Invalid recovery member page" }
                members.add(member)
                check(members.size <= 4096) { "Too many recovery members" }
            }
            token = page.nextPageToken.orEmpty()
            check(token.length <= 8192 && (token.isEmpty() || tokens.add(token) && tokens.size <= 128)) { "Invalid recovery pagination" }
        } while (token.isNotEmpty())
        return ListSecurityDomainMembersResponse(members = members)
    }
}
