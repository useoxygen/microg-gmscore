/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Base64

/** Canonical affiliation identity. A display group never grants credential-sharing authority. */
class AffiliationFacet private constructor(val id: String, val kind: Kind) {
    enum class Kind { HTTPS, HTTP, ANDROID }
    override fun equals(other: Any?) = other is AffiliationFacet && id == other.id
    override fun hashCode() = id.hashCode()
    override fun toString() = "AffiliationFacet[redacted]"
    companion object {
        fun parse(value: String): AffiliationFacet {
            if (value.isEmpty() || value.length > 4096 || value.any { it.isISOControl() } || '\\' in value) throw ProtocolException()
            val scheme = value.substringBefore(':').lowercase()
            if (scheme in setOf("https", "http")) {
                val url = value.toHttpUrlOrNull() ?: throw ProtocolException()
                val authority = try { URI(value).rawAuthority } catch (_: Exception) { throw ProtocolException() }
                if (authority.isNullOrEmpty() || '@' in authority || url.encodedPath != "/" ||
                    url.encodedQuery != null || url.encodedFragment != null) throw ProtocolException()
                return AffiliationFacet(url.toString().removeSuffix("/"), if (scheme == "https") Kind.HTTPS else Kind.HTTP)
            }
            if (scheme != "android") throw ProtocolException()
            val uri = try { URI(value) } catch (_: Exception) { throw ProtocolException() }
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in setOf("", "/")) throw ProtocolException()
            val parts = uri.rawAuthority?.split('@') ?: throw ProtocolException()
            if (parts.size != 2) throw ProtocolException()
            var hash = asciiUnescape(parts[0])
            val name = asciiUnescape(parts[1])
            if (hash.isEmpty() || !hash.all { it.isAsciiAlphaNumeric() || it in "-_=" } ||
                name.isEmpty() || !name.all { it.isAsciiAlphaNumeric() || it in "._" }) throw ProtocolException()
            while (hash.length % 4 != 0) hash += "="
            val padding = hash.indexOf('=')
            if (padding >= 0 && (hash.length - padding > 2 || hash.substring(padding).any { it != '=' })) throw ProtocolException()
            val digest = try { Base64.getUrlDecoder().decode(hash) } catch (_: IllegalArgumentException) { throw ProtocolException() }
            if (digest.size != 32 || Base64.getUrlEncoder().encodeToString(digest) != hash) throw ProtocolException()
            return AffiliationFacet("android://$hash@$name", Kind.ANDROID)
        }
        private fun Char.isAsciiAlphaNumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
        private fun asciiUnescape(value: String): String {
            val out = StringBuilder()
            var offset = 0
            while (offset < value.length) {
                val char = value[offset++]
                if (char != '%') out.append(char) else {
                    if (offset + 2 > value.length) throw ProtocolException()
                    val byte = value.substring(offset, offset + 2).toIntOrNull(16) ?: throw ProtocolException()
                    out.append(byte.toChar()); offset += 2
                }
            }
            return out.toString()
        }
    }
}

class AffiliationBranding(val name: String, val iconUrl: String?) {
    override fun toString() = "AffiliationBranding[redacted]"
}
class AffiliationFacetInfo(val facet: AffiliationFacet, val branding: AffiliationBranding?,
    val mainDomain: String?, val changePasswordUrl: String?) {
    override fun toString() = "AffiliationFacetInfo[redacted]"
}
class AffiliationSet internal constructor(facets: List<AffiliationFacetInfo>, val branding: AffiliationBranding? = null,
    val synthesized: Boolean = false) {
    val facets: List<AffiliationFacetInfo> = Collections.unmodifiableList(facets.toList())
    override fun toString() = "AffiliationSet[redacted]"
}

/** Independently decoded from the public affiliation protocol and pinned isos/isob/isoj descriptors. */
class AffiliationResponse private constructor(sharing: List<AffiliationSet>, groups: List<AffiliationSet>, extensions: Set<String>) {
    val sharing: List<AffiliationSet> = Collections.unmodifiableList(sharing.toList())
    val groups: List<AffiliationSet> = Collections.unmodifiableList(groups.toList())
    val pslExtensions: Set<String> = Collections.unmodifiableSet(extensions.toSet())

    fun credentialSharingFacets(requested: AffiliationFacet): Set<AffiliationFacet> {
        // Google does not treat insecure HTTP entries as credential-sharing affiliation matches.
        if (requested.kind == AffiliationFacet.Kind.HTTP) return emptySet()
        return sharing.singleOrNull { group -> group.facets.any { it.facet == requested } }?.facets
            ?.map { it.facet }?.filter { it.kind != AffiliationFacet.Kind.HTTP }?.toSet() ?: emptySet()
    }
    fun groupedFacets(requested: AffiliationFacet): List<AffiliationFacetInfo> =
        groups.singleOrNull { group -> group.facets.any { it.facet == requested } }?.facets ?: emptyList()

    fun info(facet: AffiliationFacet): AffiliationFacetInfo? = groups.asSequence().flatMap { it.facets.asSequence() }
        .firstOrNull { it.facet == facet } ?: sharing.asSequence().flatMap { it.facets.asSequence() }.firstOrNull { it.facet == facet }

    /** PSL equivalence is a display hint. It is deliberately absent from credentialSharingFacets. */
    fun isGrouped(requested: AffiliationFacet, candidate: AffiliationFacet): Boolean {
        if (requested == candidate || groupedFacets(requested).any { it.facet == candidate }) return true
        val domain = AffiliationDomains.mainDomain(requested, pslExtensions) ?: return false
        val candidateDomain = AffiliationDomains.mainDomain(candidate, pslExtensions) ?: return false
        if (domain == candidateDomain) return true
        return groupedFacets(requested).any { AffiliationDomains.mainDomain(it.facet, pslExtensions) == candidateDomain }
    }

    override fun toString() = "AffiliationResponse[redacted]"
    companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_REQUESTED_FACETS = 1000
        private const val MAX_FACETS = 20_000

        fun decode(raw: ByteArray, requested: Collection<AffiliationFacet>): AffiliationResponse {
            if (raw.size > MAX_RESPONSE_BYTES || requested.isEmpty() || requested.size > MAX_REQUESTED_FACETS) throw ProtocolException()
            val response = Proto.decode(raw)
            var total = 0
            fun classes(field: Int): List<AffiliationSet> {
                val result = ArrayList<AffiliationSet>()
                val seen = HashMap<AffiliationFacet, Set<AffiliationFacet>>()
                for (encoded in response.bytes(field)) {
                    val group = Proto.decode(encoded)
                    val facets = ArrayList<AffiliationFacetInfo>()
                    for (item in group.bytes(1)) {
                        if (++total > MAX_FACETS) throw ProtocolException()
                        val entry = Proto.decode(item)
                        // Unknown future facet schemes have no current authority.
                        val id = entry.optionalString(1) ?: continue
                        val facet = runCatching { AffiliationFacet.parse(id) }.getOrNull() ?: continue
                        facets.add(AffiliationFacetInfo(facet, entry.optional(2)?.let(::branding),
                            entry.optionalString(3)?.let(::domain),
                            entry.optional(4)?.let { httpsUrl(Proto.decode(it).optionalString(1)) }))
                    }
                    if (facets.isEmpty()) continue
                    val identities = facets.map { it.facet }.toSet()
                    if (identities.size != facets.size) throw ProtocolException()
                    val previous = identities.mapNotNull(seen::get)
                    if (previous.any { it != identities }) throw ProtocolException()
                    identities.forEach { seen[it] = identities }
                    if (previous.isEmpty() && identities.any { it in requested }) {
                        result.add(AffiliationSet(facets, if (field == 2) group.optional(2)?.let(::branding) else null))
                    }
                }
                for (facet in requested.toSet()) if (facet !in seen) {
                    result.add(AffiliationSet(listOf(AffiliationFacetInfo(facet, null, null, null)), synthesized = true))
                }
                return result
            }
            val sharing = classes(1)
            val grouping = classes(2)
            val extensions = response.bytes(3).map { domain(Proto.utf8(it)) }.toSet()
            if (extensions.size > MAX_FACETS) throw ProtocolException()
            return AffiliationResponse(sharing, grouping, extensions)
        }
        private fun branding(raw: ByteArray): AffiliationBranding {
            val proto = Proto.decode(raw)
            val name = proto.optionalString(1).orEmpty()
            if (name.length > 4096 || name.any(Char::isISOControl)) throw ProtocolException()
            return AffiliationBranding(name, httpsUrl(proto.optionalString(2)))
        }
        private fun httpsUrl(value: String?): String? {
            if (value == null || value.length > 4096 || value.any(Char::isISOControl) || '\\' in value) return null
            val authority = runCatching { URI(value).rawAuthority }.getOrNull() ?: return null
            if ('@' in authority) return null
            return value.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString()
        }
        private fun domain(value: String): String {
            if (value.isEmpty() || value.length > 253 || value.any { it.isISOControl() } ||
                value.any { it in "/:@?#\\%" } || value.startsWith('.') || value.endsWith('.')) throw ProtocolException()
            val url = "https://$value/".toHttpUrlOrNull() ?: throw ProtocolException()
            return url.host
        }
    }
}

object AffiliationLookup {
    /** The request contains only the first sixteen SHA-256 bits, left aligned in uint64. */
    fun request(facets: Collection<AffiliationFacet>, locale: String = "en", iconSize: Int = 40): ByteArray {
        if (facets.isEmpty() || facets.size > AffiliationResponse.MAX_REQUESTED_FACETS ||
            locale.length > 100 || locale.any { it.isISOControl() } || iconSize !in 1..1024) throw ProtocolException()
        val mask = Proto.integer(1, 1) + Proto.field(2, locale) + Proto.integer(4, iconSize.toLong()) +
            Proto.integer(5, 1) + Proto.integer(6, 1) + Proto.integer(7, 1) + Proto.integer(8, 1) + Proto.integer(9, 1)
        return facets.toSet().fold(Proto.field(1, mask) + Proto.integer(2, 16)) { out, facet ->
            val hash = MessageDigest.getInstance("SHA-256").digest(facet.id.toByteArray(Charsets.UTF_8))
            val prefix = ((hash[0].toLong() and 255) shl 56) or ((hash[1].toLong() and 255) shl 48)
            out + Proto.integer(3, prefix)
        }
    }
}

/** Chrome has distinct affiliated-password (ioxh) and display-info (ioxi) wrappers. */
object ChromeAffiliatedPassword {
    fun exact(chromePassword: ByteArray, requested: AffiliationFacet): ByteArray? {
        return affiliated(chromePassword, requested, null)
    }
    fun affiliated(chromePassword: ByteArray, requested: AffiliationFacet, response: AffiliationResponse?, verifiedSharing: Boolean = false): ByteArray? {
        val data = PasswordData.decode(Proto.decode(chromePassword).one(1))
        val actual = runCatching { AffiliationFacet.parse(data.realm) }.getOrNull() ?: return null
        val sharing = actual.kind != AffiliationFacet.Kind.HTTP && requested.kind != AffiliationFacet.Kind.HTTP &&
            (actual == requested || verifiedSharing || response?.credentialSharingFacets(requested)?.contains(actual) == true)
        val grouped = actual == requested || response?.isGrouped(requested, actual) == true
        if (!sharing && !grouped) return null
        return uiInfo(chromePassword, response) + Proto.integer(3, if (sharing) 1 else 0) + Proto.integer(4, if (grouped) 1 else 0)
    }
    fun uiInfo(chromePassword: ByteArray, response: AffiliationResponse?): ByteArray {
        val data = PasswordData.decode(Proto.decode(chromePassword).one(1))
        val facet = runCatching { AffiliationFacet.parse(data.realm) }.getOrNull()
        val brand = facet?.let { response?.info(it)?.branding }
        return Proto.field(1, chromePassword) + (brand?.let {
            Proto.field(2, Proto.field(1, it.name) + (it.iconUrl?.let { url -> Proto.field(2, url) } ?: byteArrayOf()))
        } ?: byteArrayOf())
    }
}

/** Uses OkHttp's embedded public suffix list and server additions; never authorizes a fill. */
object AffiliationDomains {
    fun mainDomain(facet: AffiliationFacet, extensions: Set<String> = emptySet()): String? {
        if (facet.kind == AffiliationFacet.Kind.ANDROID) return null
        val url = facet.id.toHttpUrlOrNull() ?: return null
        val host = url.host
        val normal = url.topPrivateDomain() ?: return host
        val extension = extensions.filter { host == it || host.endsWith(".$it") }.maxByOrNull { it.length } ?: return normal
        if (host == extension) return host
        val prefix = host.removeSuffix(".$extension")
        return prefix.substringAfterLast('.') + "." + extension
    }
}

/** Persist only inside an account-bound encrypted store; stale metadata grants no sharing authority. */
class AffiliationSnapshot private constructor(private val response: ByteArray, private val requested: List<AffiliationFacet>,
    val fetchedMillis: Long, val expiresMillis: Long) {
    fun at(nowMillis: Long): AffiliationResponse? = if (nowMillis < fetchedMillis || nowMillis >= expiresMillis) null
        else AffiliationResponse.decode(response, requested)
    fun covers(facets: Collection<AffiliationFacet>): Boolean = requested.toSet().containsAll(facets)
    fun encode(): ByteArray = requested.fold(Proto.integer(1, 1)) { out, facet -> out + Proto.field(2, facet.id) } +
        Proto.field(3, response) + Proto.integer(4, fetchedMillis) + Proto.integer(5, expiresMillis)
    override fun toString() = "AffiliationSnapshot[redacted]"
    companion object {
        // Cap our cache at the pinned reference's default base (14 days) plus jitter (3 days).
        const val MAX_AGE_MILLIS = 17 * 24 * 60 * 60 * 1000L
        fun create(response: ByteArray, requested: Collection<AffiliationFacet>, fetchedMillis: Long, expiresMillis: Long): AffiliationSnapshot {
            if (fetchedMillis < 0 || expiresMillis <= fetchedMillis || expiresMillis - fetchedMillis > MAX_AGE_MILLIS) throw ProtocolException()
            AffiliationResponse.decode(response, requested)
            return AffiliationSnapshot(response.copyOf(), requested.toSet().toList(), fetchedMillis, expiresMillis)
        }
        fun decode(raw: ByteArray): AffiliationSnapshot {
            val proto = Proto.decode(raw)
            if (proto.integer(1) != 1L) throw ProtocolException()
            return create(proto.one(3), proto.bytes(2).map { AffiliationFacet.parse(Proto.utf8(it)) },
                proto.integer(4) ?: throw ProtocolException(), proto.integer(5) ?: throw ProtocolException())
        }
    }
}
