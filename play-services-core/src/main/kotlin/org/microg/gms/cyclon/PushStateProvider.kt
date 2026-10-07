/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.gcm.McsService

/**
 * Cyclon's read-only push bridge: what microG's push screens show the owner (the apps it knows, their registrations,
 * delivered message counts and last message times, and the push connection), for Cyclon Core. Only callers holding
 * [PERMISSION] (signature|privileged, defined in this app's manifest) get answers; there is no write and nothing about
 * message content, registration tokens or app signatures.
 *
 * It runs in microG's `:persistent` process because the connection's state lives in [McsService]'s memory there.
 *
 * `call(method, arg)`:
 * - `state`: `connected`, `connected_since`, `last_heartbeat_ping`, `last_heartbeat_ack`, `last_received` (ms since
 *   the epoch, 0 when never in this process) and `network` (mobile, wifi, roaming, other; absent before a connection).
 * - `apps` (arg: one package name, or null for a page of all; extras `limit` up to [MAX_LIMIT] and `after`, the
 *   previous page's `next`): `apps`, a list of bundles with `package`, `registered`, `registered_at`, `allow_register`,
 *   `wake_for_delivery`, `last_error` (one of [ERROR_CODES], never microG's stored text, which can hold a server
 *   response with a registration token), `message_count`, `message_bytes`, `last_message_at`; and `next` when more
 *   follow. A page is bounded (at most [MAX_LIMIT] small bundles), so the reply stays far below Binder's limit.
 * Every result carries `version` ([VERSION]); an unknown method returns null.
 */
class PushStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // The settings UI shares this app's UID, but runs outside :persistent. External callers
        // still require the signature/privileged permission, including calls to call().
        if (Binder.getCallingUid() != Process.myUid()) {
            context!!.enforceCallingPermission(PERMISSION, "Reading microG's push state needs $PERMISSION")
        }
        return when (method) {
            METHOD_STATE -> state()
            METHOD_APPS -> apps(arg, extras)
            else -> null
        }?.apply { putInt(KEY_VERSION, VERSION) }
    }

    private fun state(): Bundle {
        val connection = McsService.snapshot()
        return Bundle().apply {
            putBoolean("connected", connection.connected)
            putLong("connected_since", connection.connectedSince)
            putLong("last_heartbeat_ping", connection.lastHeartbeatPing)
            putLong("last_heartbeat_ack", connection.lastHeartbeatAck)
            putLong("last_received", connection.lastReceived)
            connection.networkPref?.removePrefix(NETWORK_PREFIX)?.let { putString("network", it) }
        }
    }

    /**
     * One page of the apps microG's push database knows, in package order after [extras]' `after` (exclusive), at most
     * `limit` ([MAX_LIMIT]) of them; `next` is the cursor of the following page, absent at the end. With [packageName]
     * only that package (exact match). Only non-secret columns are read: registrations are counted and their newest time
     * taken, never their ids or signatures; the error is read bounded and reported as a code ([errorCode]).
     */
    private fun apps(packageName: String?, extras: Bundle?): Bundle {
        val limit = (extras?.getInt(KEY_LIMIT, DEFAULT_LIMIT) ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val after = extras?.getString(KEY_AFTER)
        // No package is longer, so neither is a cursor this provider gave out: an empty last page.
        if (after != null && after.length > MAX_PACKAGE_LENGTH) return Bundle().apply { putParcelableArrayList("apps", ArrayList<Bundle>()) }
        val database = GcmDatabase(context)
        try {
            val db = database.readableDatabase
            val names = ArrayList<String>()
            if (packageName != null) {
                if (packageName.length <= MAX_PACKAGE_LENGTH) names += packageName
            } else {
                // Every package either table names (noteAppRegistered always writes both; the legacy import may not).
                db.rawQuery("SELECT package_name FROM apps WHERE package_name > ? AND length(package_name) <= $MAX_PACKAGE_LENGTH " +
                        "UNION SELECT package_name FROM registrations WHERE package_name > ? AND length(package_name) <= $MAX_PACKAGE_LENGTH " +
                        "ORDER BY package_name LIMIT ${limit + 1}", arrayOf(after ?: "", after ?: "")).use { c ->
                    while (c.moveToNext()) names += c.getString(0)
                }
            }
            val more = names.size > limit
            val result = ArrayList<Bundle>()
            for (name in names.take(limit)) {
                val app = db.rawQuery("SELECT substr(last_error, 1, $MAX_ERROR_LENGTH), last_message_timestamp, total_message_count, total_message_bytes, " +
                        "allow_register, wake_for_delivery FROM apps WHERE package_name = ?", arrayOf(name)).use { c ->
                    if (!c.moveToFirst()) null else Bundle().apply {
                        errorCode(if (c.isNull(0)) null else c.getString(0))?.let { putString("last_error", it) }
                        putLong("last_message_at", c.getLong(1))
                        putLong("message_count", c.getLong(2))
                        putLong("message_bytes", c.getLong(3))
                        putBoolean("allow_register", c.isNull(4) || c.getLong(4) == 1L)
                        putBoolean("wake_for_delivery", c.isNull(5) || c.getLong(5) == 1L)
                    }
                }
                val (registrations, registeredAt) = db.rawQuery("SELECT count(*), max(timestamp) FROM registrations WHERE package_name = ?", arrayOf(name)).use { c ->
                    if (c.moveToFirst()) c.getLong(0) to (if (c.isNull(1)) 0L else c.getLong(1)) else 0L to 0L
                }
                if (app == null && registrations == 0L) continue
                result += (app ?: Bundle().apply { putBoolean("allow_register", true); putBoolean("wake_for_delivery", true) }).apply {
                    putString("package", name)
                    putBoolean("registered", registrations > 0)
                    putLong("registered_at", registeredAt)
                }
            }
            return Bundle().apply {
                putParcelableArrayList("apps", result)
                if (more && packageName == null) putString(KEY_NEXT, names[limit - 1])
            }
        } finally {
            database.close()
        }
    }

    // A call()-only provider: no rows, no writes.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        /**
         * The registration error as a code. microG stores the server's `Error=` code, but also raw response text
         * (PushRegisterManager: a registration without a token, an unregistration answered with an unexpected token) and
         * the legacy import's text: only known codes pass, anything else is `other`.
         */
        @JvmStatic
        fun errorCode(stored: String?): String? {
            val error = stored?.trim().orEmpty()
            if (error.isEmpty()) return null
            return KNOWN_ERRORS[error] ?: "other"
        }

        private val KNOWN_ERRORS = mapOf(
            "SERVICE_NOT_AVAILABLE" to "service_not_available", "AUTHENTICATION_FAILED" to "authentication_failed",
            "INVALID_SENDER" to "invalid_sender", "PHONE_REGISTRATION_ERROR" to "phone_registration_error",
            "INVALID_PARAMETERS" to "invalid_parameters", "MISSING_SERVER" to "missing_server",
            "TOO_MANY_REGISTRATIONS" to "too_many_registrations", "RETRY_LATER" to "retry_later",
            "TIMEOUT" to "timeout", "Invalid argument for the given fid" to "invalid_fid",
            "Unregistration error" to "unregistration_failed",
        )
        val ERROR_CODES = KNOWN_ERRORS.values.toSet() + "other"

        const val PERMISSION = "ai.cyclon.microg.permission.READ_PUSH_STATE"
        const val METHOD_STATE = "state"
        const val METHOD_APPS = "apps"
        const val KEY_VERSION = "version"
        const val KEY_LIMIT = "limit"
        const val KEY_AFTER = "after"
        const val KEY_NEXT = "next"
        /** Version 2: paged apps, error codes instead of microG's stored error text. */
        const val VERSION = 2
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        /** Longer than any Android package name; longer rows are skipped rather than sent. */
        const val MAX_PACKAGE_LENGTH = 255
        /** Enough for every known error; the code mapping needs no more of a stored error. */
        const val MAX_ERROR_LENGTH = 64
        private const val NETWORK_PREFIX = "gcm_network_"
    }
}
