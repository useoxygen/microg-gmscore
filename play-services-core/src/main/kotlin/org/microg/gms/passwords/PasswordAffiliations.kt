/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import androidx.core.os.LocaleListCompat
import com.google.android.gms.BuildConfig

class PasswordAffiliationDisplay(val name: String?, val iconUrl: String?, val changePasswordUrl: String?, val mainDomain: String?) {
    override fun toString() = "PasswordAffiliationDisplay[redacted]"
}

/** The cache lives in the selected account's encrypted vault and inherits its removal/access checks. */
class PasswordAffiliations(context: Context, private val repository: PasswordRepository) {
    private val locale = if (android.os.Build.VERSION.SDK_INT >= 24) context.resources.configuration.locales.let {
        if (it.isEmpty) "en" else it[0].toLanguageTag()
    } else LocaleListCompat.create(context.resources.configuration.locale).toLanguageTags()
    private val iconSize = (context.resources.displayMetrics.density * 40).toInt().coerceIn(1, 1024)
    private val transport: AffiliationTransport? = BuildConfig.PASSWORD_AFFILIATION_API_KEY.takeIf { it.isNotBlank() }
        ?.let(::GoogleAffiliationTransport)

    fun lookup(facets: Collection<AffiliationFacet>, refresh: Boolean = true): AffiliationResponse? {
        if (!repository.enabled || facets.isEmpty() || facets.toSet().size > AffiliationResponse.MAX_REQUESTED_FACETS) return null
        return repository.store.locked { session ->
            if (!repository.enabled) throw VaultLockedException()
            val state = session.load()
            val engine = AffiliationRefresh(object : AffiliationCacheStore {
                override fun read() = state.affiliationCache
                override fun write(value: ByteArray) {
                    if (!repository.enabled) throw VaultLockedException()
                    state.affiliationCache = value
                    session.save(state)
                }
            }, transport)
            val result = if (refresh) {
                try { engine.refresh(facets, locale, iconSize) }
                catch (_: AffiliationLookupException) { engine.cached(facets) }
                catch (_: ProtocolException) { engine.cached(facets) }
            } else engine.cached(facets)
            // load rechecks the account identity after I/O even when no replacement was saved.
            session.load()
            if (!repository.enabled) throw VaultLockedException()
            result
        }
    }

    fun refresh() {
        if (transport == null || !repository.enabled) return
        val entries = repository.list()
        try {
            val facets = entries.mapNotNull { runCatching { AffiliationFacet.parse(it.data.realm) }.getOrNull() }.toSet()
            lookup(facets)
        } finally { entries.forEach { it.chromeBytes.fill(0) } }
    }

    /** Cache-only so an owner screen never performs metadata network I/O on its main thread. */
    fun displayInfo(facet: AffiliationFacet): PasswordAffiliationDisplay? {
        val response = lookup(listOf(facet), refresh = false) ?: return null
        val info = response.info(facet) ?: return null
        return PasswordAffiliationDisplay(info.branding?.name?.takeIf { it.isNotBlank() }, info.branding?.iconUrl,
            info.changePasswordUrl, info.mainDomain ?: AffiliationDomains.mainDomain(facet, response.pslExtensions))
    }

    companion object {
        fun cachedDisplayInfo(cache: ByteArray?, realm: String): PasswordAffiliationDisplay? = runCatching {
            val facet = AffiliationFacet.parse(realm)
            val response = AffiliationRefresh(object : AffiliationCacheStore {
                override fun read() = cache
                override fun write(value: ByteArray) { throw UnsupportedOperationException() }
            }, null).cached(listOf(facet)) ?: return@runCatching null
            val info = response.info(facet) ?: return@runCatching null
            PasswordAffiliationDisplay(info.branding?.name?.takeIf { it.isNotBlank() }, info.branding?.iconUrl,
                info.changePasswordUrl, info.mainDomain ?: AffiliationDomains.mainDomain(facet, response.pslExtensions))
        }.getOrNull()
    }
}
