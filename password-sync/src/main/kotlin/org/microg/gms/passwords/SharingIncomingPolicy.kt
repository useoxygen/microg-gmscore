/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** The six incoming partitions in GMS bmhe/bmgz. Grouping and affiliation confer no authority here. */
enum class SharingIncomingDisposition { NEW, DUPLICATE, NON_SHARED_UPDATE, SHARED_SAME_SENDER_UPDATE, SHARED_DIFFERENT_SENDER_UPDATE }

class SharingPasswordBatch(val passwords: List<PasswordData>, val invalidCount: Int) {
    override fun toString() = "SharingPasswordBatch[redacted]"
}

object SharingIncomingPolicy {
    // PasswordSharing__is_auto_approving_updates_from_same_sender_enabled (jdsk), pinned default.
    // The enabled rollout branch requires a separate atomic compare-and-update consumer.
    const val AUTO_APPROVE_SAME_SENDER_UPDATES = false

    /** bmha.a compares these exact strings, including element names and the full origin. */
    fun sameIdentity(first: PasswordData, second: PasswordData): Boolean {
        val a = Proto.decode(first.encode()); val b = Proto.decode(second.encode())
        return listOf(6, 3, 2, 5, 7).all { a.optionalString(it).orEmpty() == b.optionalString(it).orEmpty() }
    }

    fun classify(incoming: PasswordData, existing: PasswordData?): SharingIncomingDisposition {
        if (existing == null) return SharingIncomingDisposition.NEW
        if (!sameIdentity(incoming, existing)) throw ConflictException()
        if (incoming.password == existing.password) return SharingIncomingDisposition.DUPLICATE
        val old = Proto.decode(existing.encode()); val next = Proto.decode(incoming.encode())
        if (old.integer(13) != 5L) return SharingIncomingDisposition.NON_SHARED_UPDATE
        return if (old.optionalString(23).orEmpty() == next.optionalString(23).orEmpty())
            SharingIncomingDisposition.SHARED_SAME_SENDER_UPDATE else SharingIncomingDisposition.SHARED_DIFFERENT_SENDER_UPDATE
    }

    /** Server protobuf ordering and extra metadata do not change the fields this import wrote. */
    fun sameImportedPassword(first: PasswordData, second: PasswordData): Boolean {
        val a = Proto.decode(first.encode()); val b = Proto.decode(second.encode())
        return listOf(2, 3, 5, 6, 7, 8, 15, 16, 23, 24, 27).all {
            a.optionalString(it).orEmpty() == b.optionalString(it).orEmpty()
        } && listOf(1, 11, 13, 25, 26).all { (a.integer(it) ?: 0) == (b.integer(it) ?: 0) }
    }

    /** bmhe validates per element; Kotlin String.length, like Java, counts UTF-16 code units. */
    internal fun valid(password: Proto): Boolean = password.optionalString(8).orEmpty().isNotEmpty() &&
        password.optionalString(3).orEmpty().isNotEmpty() && password.optionalString(2).orEmpty().isNotEmpty() &&
        listOf(5, 6, 7, 8, 2, 15, 24).all { password.optionalString(it).orEmpty().length <= 1024 }
}
