/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Debug native account-selection receipt. It proves selection scope, never Google enrollment or provider acceptance. */
internal class PasswordAcceptanceSelection(val name: String, val accountId: String, val issued: Long, val boot: Int,
    val run: String, val role: String) {
    init {
        PasswordAcceptanceAccounts.checked(run, role)
        check(name.isNotBlank() && accountId.isNotBlank() && name.length <= 1024 && accountId.length <= 1024 &&
            listOf(name, accountId).none { value -> value.any(Char::isISOControl) } && issued >= 0 && boot >= 0)
    }
    fun encode(): ByteArray = Proto.field(1, name) + Proto.field(2, accountId) + Proto.integer(3, issued) +
        Proto.integer(4, boot.toLong()) + Proto.field(5, run) + Proto.field(6, role)
    fun verify(expectedRun: String, expectedRole: String, currentBoot: Int, now: Long, currentName: String?, currentId: String?) {
        check(run == expectedRun && role == expectedRole && boot == currentBoot && now >= issued && now - issued < 30 * 60 * 1000L &&
            name == currentName && accountId == currentId)
    }
    override fun toString() = "PasswordAcceptanceSelection[redacted]"
    companion object {
        fun <T> capture(verify: () -> Unit, create: () -> T): T {
            verify()
            return create().also { verify() }
        }
        fun decode(raw: ByteArray): PasswordAcceptanceSelection = Proto.decode(raw).let { p ->
            val boot = p.integer(4)?.takeIf { it in 0..Int.MAX_VALUE } ?: throw ProtocolException()
            PasswordAcceptanceSelection(p.string(1), p.string(2), p.integer(3) ?: throw ProtocolException(), boot.toInt(), p.string(5), p.string(6))
        }
    }
}
