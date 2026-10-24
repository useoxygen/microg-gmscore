/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Chrome ListOptions: creation upper/lower bounds, blocked state and issue presence. */
class PasswordFilter(val query: String?, val blockedFilter: Int, val issueFilter: Int,
    val createdBeforeMillis: Long?, val createdAfterMillis: Long?) {
    init {
        if (blockedFilter !in 0..2 || issueFilter !in 0..2 || (query?.length ?: 0) > 4096) throw ProtocolException()
    }
    fun accepts(data: PasswordData): Boolean {
        if (!query.isNullOrEmpty() && !data.realm.contains(query, ignoreCase = true)) return false
        if (blockedFilter == 1 && data.blocked || blockedFilter == 2 && !data.blocked) return false
        if (issueFilter == 1 && data.hasIssues || issueFilter == 2 && !data.hasIssues) return false
        if (createdBeforeMillis != null && data.createdWindowsMicros > windowsMicros(createdBeforeMillis)) return false
        if (createdAfterMillis != null && data.createdWindowsMicros < windowsMicros(createdAfterMillis)) return false
        return true
    }
    private fun windowsMicros(millis: Long): Long = try {
        Math.multiplyExact(Math.addExact(millis, 11_644_473_600_000L), 1000L)
    } catch (_: ArithmeticException) { throw ProtocolException() }
    override fun toString(): String = "PasswordFilter[redacted]"
}
