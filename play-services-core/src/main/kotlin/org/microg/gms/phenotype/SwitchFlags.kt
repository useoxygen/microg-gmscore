/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import com.google.android.gms.phenotype.Flag

/** Local Switch configuration, not a server rollout assignment or a sync-mode change. */
object SwitchFlags {
    const val NAMESPACE = "com.google.android.apps.pixelmigrate"
    const val RESTORE_TO_GOOGLE = "D2d__enable_contacts_restore_to_google_account"
    const val IOS_APP_MATCHING_LIMIT = "backup_ios_app_matching_service_request_apps_limit"
    const val IOS_APP_MATCHING_LIMIT_VALUE = 1000L

    // Switch 1140078 rct.java:164 defaults this off; dou.java:2469 then passes no
    // account to the iPhone writer despite ecl.java:748-787 displaying a Google account.
    // Enabling its existing path lets Switch choose and disclose the visible account.
    //
    // Switch 1140078 eew.java:157-160 sends only the first IOS_APP_MATCHING_LIMIT iPhone
    // bundle IDs (default 200) to appmapping in a single request and never sends the rest,
    // so they land in "couldn't be copied" unchecked. The server answered every ID of
    // 200..2000-ID requests on 2026-10-30 (CYC-968), so the cap is client-side only.
    private fun values(namespace: String?): Map<String, Any>? =
        if (namespace == NAMESPACE) mapOf(RESTORE_TO_GOOGLE to true, IOS_APP_MATCHING_LIMIT to IOS_APP_MATCHING_LIMIT_VALUE) else null

    @JvmStatic fun rows(namespace: String?): Map<String, String>? =
        values(namespace)?.mapValues { (_, value) -> value.toString() }

    @JvmStatic fun flags(namespace: String?): Array<Flag>? =
        values(namespace)?.map { (name, value) ->
            when (value) {
                is Boolean -> Flag(name, value, 0)
                is Long -> Flag(name, value, 0)
                else -> throw IllegalStateException("Unsupported Switch flag type")
            }
        }?.toTypedArray()
}
