/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.Context

/** Imports may merge into sync-owned rows. Hold every contact mutation until explicit owner review. */
class ContactMigrationPrivacy(context: Context) {
    private val prefs = context.getSharedPreferences("contacts-migration-privacy", Context.MODE_PRIVATE)
    val held: Boolean get() = prefs.getBoolean("uploadHold", false)
    fun beginMigration() { check(prefs.edit().putBoolean("uploadHold", true).commit()) }
    // Only the owner confirmation in ContactsSyncActivity calls this. Never auto-release on success/cancel.
    fun ownerResume() { check(prefs.edit().putBoolean("uploadHold", false).commit()) }
}
