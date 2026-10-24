/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** One account/scope's in-memory token, owned by a single sync operation. */
class PasswordOAuthSession(private val load: () -> String?, private val invalidate: (String) -> Unit) {
    private var current: String? = null

    @Synchronized fun token(): String = current ?: load()?.takeIf { it.isNotBlank() }
        ?.also { current = it } ?: throw VaultLockedException()

    @Synchronized fun reject(value: String) {
        if (current == value) current = null
        invalidate(value)
    }

    override fun toString(): String = "PasswordOAuthSession[redacted]"
}
