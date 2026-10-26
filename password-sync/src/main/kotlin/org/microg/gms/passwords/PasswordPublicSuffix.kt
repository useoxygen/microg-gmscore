/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class WebPasswordMatch { NONE, EXACT, PUBLIC_SUFFIX }

/** Chromium145 psl_matching_helper: HTML web origins, private registries, and the Google sign-in exception. */
object PasswordPublicSuffix {
    fun match(storedRealm: String, requestedRealm: String): WebPasswordMatch {
        val stored = runCatching { AffiliationFacet.parse(storedRealm) }.getOrNull() ?: return WebPasswordMatch.NONE
        val requested = runCatching { AffiliationFacet.parse(requestedRealm) }.getOrNull() ?: return WebPasswordMatch.NONE
        if (stored.kind == AffiliationFacet.Kind.ANDROID || requested.kind == AffiliationFacet.Kind.ANDROID) return WebPasswordMatch.NONE
        if (stored == requested) return WebPasswordMatch.EXACT
        val first = stored.id.toHttpUrlOrNull() ?: return WebPasswordMatch.NONE
        val second = requested.id.toHttpUrlOrNull() ?: return WebPasswordMatch.NONE
        if (first.scheme != second.scheme || first.port != second.port) return WebPasswordMatch.NONE
        fun within(host: String, domain: String) = host == domain || host.endsWith(".$domain")
        if (within(first.host, "google.com") && within(second.host, "google.com")) {
            fun allowed(host: String) = within(host, "accounts.google.com") || within(host, "myaccount.google.com")
            return if (allowed(first.host) && allowed(second.host)) WebPasswordMatch.PUBLIC_SUFFIX else WebPasswordMatch.NONE
        }
        // OkHttp's embedded PSL includes private registries (e.g. appspot.com), wildcard rules, and exceptions.
        val domain = first.topPrivateDomain() ?: return WebPasswordMatch.NONE
        return if (domain == second.topPrivateDomain()) WebPasswordMatch.PUBLIC_SUFFIX else WebPasswordMatch.NONE
    }

    /** The stored HTML scheme and origin/realm consistency are authority checks, independent of the hostname relation. */
    fun canFill(data: PasswordData, requestedRealm: String): Boolean = data.canFillRealm(data.realm) &&
        match(data.realm, requestedRealm) != WebPasswordMatch.NONE
}
