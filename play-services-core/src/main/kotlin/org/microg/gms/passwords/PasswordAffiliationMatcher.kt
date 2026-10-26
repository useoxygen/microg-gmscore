/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context

/** Requested realms must already come from the authenticated caller/target, never from grouping or model text. */
class PasswordAffiliationMatcher(context: Context, private val vault: PasswordVault) {
    private val links = PasswordAssetLinks(context.applicationContext)
    private val google = (vault as? PasswordRepository)?.let { PasswordAffiliations(context, it) }
    private val deadline = android.os.SystemClock.elapsedRealtime() + 3500
    private val store = when (vault) {
        is PasswordRepository -> vault.store
        is DevicePasswordVault -> vault.store
        else -> null
    }
    fun canFill(data: PasswordData, requestedRealm: String): Boolean = vault.enabled &&
        (PasswordPublicSuffix.canFill(data, requestedRealm) || canShare(data, requestedRealm))

    /** Chrome field3 means verified credential-sharing affiliation, never the separate PSL relation. */
    fun canShare(data: PasswordData, requestedRealm: String): Boolean = vault.enabled && data.canFillRealm(data.realm) &&
        (data.canFillRealm(requestedRealm) || canFillRealm(data.realm, requestedRealm))

    fun canFillRealm(storedRealm: String, requestedRealm: String): Boolean {
        if (!vault.enabled) return false
        val stored = runCatching { AffiliationFacet.parse(storedRealm) }.getOrNull() ?: return false
        val requested = runCatching { AffiliationFacet.parse(requestedRealm) }.getOrNull() ?: return false
        if (stored == requested) return true
        if (stored.kind == AffiliationFacet.Kind.HTTP || requested.kind == AffiliationFacet.Kind.HTTP) return false
        val app = when {
            stored.kind == AffiliationFacet.Kind.ANDROID && requested.kind == AffiliationFacet.Kind.HTTPS -> stored
            requested.kind == AffiliationFacet.Kind.ANDROID && stored.kind == AffiliationFacet.Kind.HTTPS -> requested
            else -> null
        }
        if (app != null && !links.isInstalled(app)) return false
        val response = google?.lookup(listOf(requested), refresh = false)
        if (response?.credentialSharingFacets(requested)?.contains(stored) == true) return true
        val site = if (stored.kind == AffiliationFacet.Kind.HTTPS) stored else requested
        if (app == null || site.kind != AffiliationFacet.Kind.HTTPS) return false
        val accountStore = store ?: return false
        return accountStore.locked { session ->
            if (!vault.enabled) return@locked false
            val state = session.load()
            val cache = DirectAffiliationProofs(object : AffiliationCacheStore {
                override fun read() = state.directAffiliationProofs
                override fun write(value: ByteArray) {
                    if (!vault.enabled) throw VaultLockedException()
                    state.directAffiliationProofs = value; session.save(state)
                }
            })
            val allowed = cache.allows(site, app) { links.allows(site, app, deadline - android.os.SystemClock.elapsedRealtime()) }
            session.load() // Account identity must still be current after a direct proof request.
            vault.enabled && allowed && links.isInstalled(app)
        }
    }
}
