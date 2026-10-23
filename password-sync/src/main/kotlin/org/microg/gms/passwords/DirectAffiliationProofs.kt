/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Direct DAL evidence has separate provenance and lifetimes from Google grouping/branding metadata. */
class DirectAffiliationProofs(private val store: AffiliationCacheStore, private val now: () -> Long = System::currentTimeMillis) {
    private class Proof(val site: AffiliationFacet, val app: AffiliationFacet, val allowed: Boolean,
        val fetched: Long, val expires: Long) {
        fun encode() = Proto.field(1, site.id) + Proto.field(2, app.id) + Proto.integer(3, if (allowed) 1 else 0) +
            Proto.integer(4, fetched) + Proto.integer(5, expires)
    }
    fun allows(site: AffiliationFacet, app: AffiliationFacet, verify: () -> Boolean): Boolean {
        if (site.kind != AffiliationFacet.Kind.HTTPS || app.kind != AffiliationFacet.Kind.ANDROID) return false
        val timestamp = now()
        if (timestamp < 0 || timestamp > Long.MAX_VALUE - POSITIVE_AGE_MILLIS) throw ProtocolException()
        val proofs = load().filter { timestamp >= it.fetched && timestamp < it.expires }.toMutableList()
        proofs.singleOrNull { it.site == site && it.app == app }?.let { return it.allowed }
        fun save(proof: Proof) {
            proofs.removeAll { it.site == site && it.app == app }
            if (proofs.size >= MAX_PROOFS) proofs.removeAt(proofs.indices.minByOrNull { proofs[it].fetched }!!)
            proofs.add(proof)
            var encoded = proofs.fold(Proto.integer(1, 1)) { out, value -> out + Proto.field(2, value.encode()) }
            while (encoded.size > 1024 * 1024 && proofs.size > 1) {
                proofs.removeAt(proofs.indices.minByOrNull { proofs[it].fetched }!!)
                encoded = proofs.fold(Proto.integer(1, 1)) { out, value -> out + Proto.field(2, value.encode()) }
            }
            if (encoded.size > 1024 * 1024) throw ProtocolException()
            store.write(encoded)
        }
        // A crash or failed remote proof leaves a bounded denial, never an unverified grant.
        save(Proof(site, app, false, timestamp, timestamp + NEGATIVE_AGE_MILLIS))
        if (!verify()) return false
        val completed = now()
        if (completed < timestamp || completed > Long.MAX_VALUE - POSITIVE_AGE_MILLIS) return false
        save(Proof(site, app, true, completed, completed + POSITIVE_AGE_MILLIS))
        return true
    }
    private fun load(): List<Proof> {
        val raw = store.read() ?: return emptyList()
        if (raw.size > 1024 * 1024) throw ProtocolException()
        val proto = Proto.decode(raw)
        if (proto.integer(1) != 1L || proto.bytes(2).size > MAX_PROOFS) throw ProtocolException()
        val proofs = proto.bytes(2).map { encoded ->
            val entry = Proto.decode(encoded)
            val site = AffiliationFacet.parse(entry.string(1)); val app = AffiliationFacet.parse(entry.string(2))
            val allowed = entry.integer(3) ?: throw ProtocolException()
            val fetched = entry.integer(4) ?: throw ProtocolException(); val expires = entry.integer(5) ?: throw ProtocolException()
            val age = if (allowed == 1L) POSITIVE_AGE_MILLIS else NEGATIVE_AGE_MILLIS
            if (site.kind != AffiliationFacet.Kind.HTTPS || app.kind != AffiliationFacet.Kind.ANDROID || allowed !in 0..1 ||
                fetched < 0 || expires <= fetched || expires - fetched > age) throw ProtocolException()
            Proof(site, app, allowed == 1L, fetched, expires)
        }
        if (proofs.map { it.site to it.app }.toSet().size != proofs.size) throw ProtocolException()
        return proofs
    }
    override fun toString() = "DirectAffiliationProofs[redacted]"
    companion object {
        const val POSITIVE_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val NEGATIVE_AGE_MILLIS = 10 * 60 * 1000L
        const val MAX_PROOFS = 256
    }
}
