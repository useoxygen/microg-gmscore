/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

internal object FolsomDomainPolicy {
    fun valid(domain: String): Boolean = Regex("[a-z][a-z0-9_]{0,63}").matches(domain)
    fun matches(resource: String?, domain: String): Boolean =
        valid(domain) && resource == "users/me/securitydomains/$domain"
}
