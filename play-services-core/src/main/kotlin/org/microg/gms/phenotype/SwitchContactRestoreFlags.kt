/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import com.google.android.gms.phenotype.Flag

/** Local Switch configuration, not a server rollout assignment or a sync-mode change. */
object SwitchContactRestoreFlags {
    const val NAMESPACE = "com.google.android.apps.pixelmigrate"
    const val RESTORE_TO_GOOGLE = "D2d__enable_contacts_restore_to_google_account"

    // Switch 1140078 rct.java:164 defaults this off; dou.java:2469 then passes no
    // account to the iPhone writer despite ecl.java:748-787 displaying a Google account.
    // Enabling its existing path lets Switch choose and disclose the visible account.
    private fun values(namespace: String?): Map<String, Boolean>? =
        if (namespace == NAMESPACE) mapOf(RESTORE_TO_GOOGLE to true) else null

    @JvmStatic fun rows(namespace: String?): Map<String, String>? =
        values(namespace)?.mapValues { (_, value) -> value.toString() }

    @JvmStatic fun flags(namespace: String?): Array<Flag>? =
        values(namespace)?.map { (name, value) -> Flag(name, value, 0) }?.toTypedArray()
}
