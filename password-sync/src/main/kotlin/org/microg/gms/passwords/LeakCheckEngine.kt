/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

class LeakCheckSnapshot(val parent: String, val passwords: List<PasswordData>) {
    init {
        PasswordApi.checkedParent(parent)
        if (passwords.size > 10_000 || passwords.map { it.tag() }.toSet().size != passwords.size) throw ProtocolException()
    }
    fun verify(parent: String, current: List<PasswordData>) {
        if (parent != this.parent) throw VaultResetException()
        val records = current.associateBy { it.tag() }
        if (records.size != current.size || current.size != passwords.size || passwords.any { old ->
                records[old.tag()]?.let { MessageDigest.isEqual(it.encode(), old.encode()) } != true }) throw ConflictException()
    }
    override fun toString() = "LeakCheckSnapshot[redacted]"
}
class LeakCheckChange(val original: PasswordData, val replacement: PasswordData) {
    override fun toString() = "LeakCheckChange[redacted]"
}
interface LeakCheckVault {
    /** A complete committed snapshot, with no unresolved password mutation or reset. */
    fun snapshot(): LeakCheckSnapshot
    /** Revalidate the full snapshot, atomically enqueue changes, then verify their real commits. */
    fun commit(snapshot: LeakCheckSnapshot, changes: List<LeakCheckChange>)
}
data class LeakCheckProgress(val checked: Int, val total: Int, val saving: Boolean = false)
data class LeakCheckAssessment(val checked: Int, val leaked: Int, val unassessed: Int, val completedMillis: Long, val knownLeaked: Int = 0)

/** Source bnkr: add fresh positive issues only; existing issue presence/mutes and negative results are retained. */
object LeakCheckIssues {
    fun isKnownLeaked(data: PasswordData): Boolean = Proto.decode(data.encode()).optional(19)?.let { Proto.decode(it).optional(1) != null } == true
    fun withLeaked(data: PasswordData, nowMillis: Long): PasswordData {
        val password = Proto.decode(data.encode())
        val issues = Proto.decode(password.optional(19) ?: byteArrayOf())
        if (issues.optional(1) != null) return data
        if (nowMillis < 0 || nowMillis > Long.MAX_VALUE / 1000 - 11_644_473_600_000L) throw ProtocolException()
        val issue = Proto.integer(1, (nowMillis + 11_644_473_600_000L) * 1000)
        return PasswordData.decode(password.replace(19, issues.replace(1, issue).encode()).encode())
    }
}

/** Owner-requested assessment. No metadata changes are prepared until every requested check has completed. */
class LeakCheckEngine(private val protocol: LeakCheckProtocol, private val vault: LeakCheckVault,
    private val check: () -> Unit = {}, private val progress: (LeakCheckProgress) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Subject(val username: String, val password: String) { override fun toString() = "LeakCheckSubject[redacted]" }
    fun assess(): LeakCheckAssessment {
        check()
        val snapshot = vault.snapshot()
        check()
        val candidates = snapshot.passwords.filter { !it.blocked && it.password.isNotEmpty() }
        val groups = candidates.groupBy { Subject(LeakCheckCrypto.canonicalize(it.username), it.password) }
        // bnjn.c short-circuits an already compromised username/password group.
        val known = groups.filterValues { passwords -> passwords.any(LeakCheckIssues::isKnownLeaked) }
        val knownLeaked = known.values.sumOf { it.size }
        val unassessed = groups.filter { (subject, _) -> subject !in known && subject.username.isEmpty() }.values.sumOf { it.size }
        val total = candidates.size - unassessed - knownLeaked
        progress(LeakCheckProgress(0, total))
        var checked = 0; var leaked = 0
        val positives = ArrayList<PasswordData>().apply { known.values.forEach(::addAll) }
        for ((subject, passwords) in groups) {
            check()
            if (subject in known || subject.username.isEmpty()) continue
            if (protocol.check(subject.username, subject.password)) { positives.addAll(passwords); leaked += passwords.size }
            check()
            checked += passwords.size
            progress(LeakCheckProgress(checked, total))
        }
        check()
        val detected = now()
        val changes = positives.mapNotNull { original ->
            val replacement = LeakCheckIssues.withLeaked(original, detected)
            if (replacement === original) null else LeakCheckChange(original, replacement)
        }
        progress(LeakCheckProgress(checked, total, saving = true))
        vault.commit(snapshot, changes)
        check()
        return LeakCheckAssessment(checked, leaked, unassessed, now(), knownLeaked)
    }
}
