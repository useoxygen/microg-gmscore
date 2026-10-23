/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Passwords ListWebauthnCredentials iake/iakf and mutations iaja/iala/iajg, pinned GMS 26.11. */
class PasskeyApi(private val transport: PasswordTransport) {
    fun snapshot(parent: String): List<ByteArray> {
        PasswordApi.checkedParent(parent)
        val records = ArrayList<ByteArray>()
        val tags = HashSet<String>()
        val names = HashSet<String>()
        var total = 0
        val pages = HashSet<String>()
        var page: ByteArray? = null
        repeat(100) {
            val result = Proto.decode(transport.invoke(PasswordMethod.LIST_WEBAUTHN, Proto.field(1, parent) +
                (page?.let { Proto.field(3, it) } ?: byteArrayOf())))
            for (record in result.bytes(1)) {
                val value = Proto.decode(record)
                checkedName(value.string(1), parent)
                if (records.size >= 10_000 || record.size > Proto.MAX_BYTES - total || !tags.add(value.string(2)) ||
                    !names.add(value.string(1))) throw ProtocolException()
                total += record.size
                records.add(record)
            }
            page = result.optional(2)?.takeIf { it.isNotEmpty() }
            if (page == null) return records
            if (!pages.add(SyncedPasskey.hex(page!!))) throw ProtocolException()
        }
        throw ProtocolException()
    }
    fun create(parent: String, resource: ByteArray, token: ByteArray): ByteArray = transport.invoke(PasswordMethod.CREATE_WEBAUTHN,
        Proto.field(1, PasswordApi.checkedParent(parent)) + Proto.field(2, resource) + Proto.field(4, token))
    fun update(resource: ByteArray, token: ByteArray): ByteArray = transport.invoke(PasswordMethod.UPDATE_WEBAUTHN,
        Proto.field(1, checkedName(Proto.decode(resource).string(1))) + Proto.field(2, resource) + Proto.field(4, token))
    fun delete(resource: ByteArray, token: ByteArray) {
        val p = Proto.decode(resource)
        // iajg field 5 is an optional empty ioyo message, unrelated to the resource's update_time (field 8).
        transport.invoke(PasswordMethod.DELETE_WEBAUTHN, Proto.field(1, checkedName(p.string(1))) + Proto.field(4, token))
    }
    override fun toString(): String = "PasskeyApi[redacted]"
    companion object {
        fun checkedName(value: String, parent: String? = null): String {
            // The pinned contract calls this an opaque ID. Do not invent a collection spelling or derive it from RP.
            if (value.isEmpty() || value.length > 4096 || value.any { it.isISOControl() }) throw ProtocolException()
            if (parent != null && value.startsWith("users/")) {
                if (!value.startsWith("$parent/")) throw ProtocolException()
            }
            return value
        }
    }
}
