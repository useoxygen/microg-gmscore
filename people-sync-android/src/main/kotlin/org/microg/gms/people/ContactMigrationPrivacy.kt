/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.Context

/** Optional developer fixture control. Normal transfers retain Google's account upload behavior. */
class ContactMigrationPrivacy(context: Context) {
    private val prefs = context.getSharedPreferences("contacts-migration-privacy", Context.MODE_PRIVATE)
    var developerControlsEnabled: Boolean
        get() = prefs.getBoolean("developerControlsEnabled", false)
        set(value) { check(prefs.edit().putBoolean("developerControlsEnabled", value).remove("uploadHold").commit()) }
    // Ignore old round1 holds unless the developer explicitly opts in to the new control.
    val held: Boolean get() = developerControlsEnabled && prefs.getBoolean("uploadHold", false)
    fun beginMigration() {
        if (developerControlsEnabled) check(prefs.edit().putBoolean("uploadHold", true).commit())
    }
    fun ownerResume() { check(prefs.edit().putBoolean("uploadHold", false).commit()) }
}
