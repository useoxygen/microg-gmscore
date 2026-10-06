/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.content.Context
import java.security.MessageDigest

/** Private receipt, scoped to the native account and exact check-in ID. No auth tokens. */
internal class EnrollmentSession(context: Context, account: String, id: Long) {
    private val prefs = context.getSharedPreferences("cyclon_google_enrollment", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest("$account\n$id".toByteArray())
        .joinToString("") { "%02x".format(it) }
    val accepted get() = prefs.getString(key, null) == "accepted"
    val attempted get() = prefs.getString(key, null) in setOf("attempted", "accepted")
    // Synchronous persistence precedes the one external write; unknown outcomes never replay.
    fun admit(): Boolean = !attempted && prefs.edit().putString(key, "attempted").commit()
    fun accept(): Boolean = prefs.edit().putString(key, "accepted").commit()
}
