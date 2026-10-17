// Modified by Cyclon in 2026: record when a registration was last tried, so failures back off.
/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.gcm.registeration

import google.internal.notifications.v1.RegistrationStatus
import org.json.JSONObject

data class ChimeGmsAccount(
    val id: String,
    val accountName: String,
    val representativeTargetId: String?,
    val registrationStatus: RegistrationStatus?,
    val obfuscatedGaiaId: String?,
    /** When this registration was last tried (ms since the epoch); 0 for records written before it was kept. */
    val updatedAt: Long = 0,
) {

    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_ACCOUNT_NAME, accountName)
            put(KEY_REPRESENTATIVE_TARGET_ID, representativeTargetId)
            put(KEY_REGISTRATION_STATUS, registrationStatus?.value)
            put(KEY_OBFUSCATED_GAIA_ID, obfuscatedGaiaId)
            put(KEY_UPDATED_AT, updatedAt)
        }.toString()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_ACCOUNT_NAME = "accountName"
        private const val KEY_REPRESENTATIVE_TARGET_ID = "representativeTargetId"
        private const val KEY_REGISTRATION_STATUS = "registrationStatus"
        private const val KEY_OBFUSCATED_GAIA_ID = "obfuscatedGaiaId"
        private const val KEY_UPDATED_AT = "updatedAt"

        fun parseJson(json: String): ChimeGmsAccount {
            val jsonObject = JSONObject(json)
            return ChimeGmsAccount(
                id = jsonObject.optString(KEY_ID),
                accountName = jsonObject.optString(KEY_ACCOUNT_NAME),
                representativeTargetId = jsonObject.optString(KEY_REPRESENTATIVE_TARGET_ID),
                registrationStatus = RegistrationStatus.fromValue(jsonObject.optInt(KEY_REGISTRATION_STATUS)),
                obfuscatedGaiaId = jsonObject.optString(KEY_OBFUSCATED_GAIA_ID),
                updatedAt = jsonObject.optLong(KEY_UPDATED_AT, 0)
            )
        }
    }
}

/** After a failed notification registration, automatic triggers (push reconnects) wait this long before retrying. */
const val CHIME_RETRY_AFTER_FAILURE_MILLIS = 60 * 60 * 1000L

/**
 * Whether an account with [stored] registration (null when never tried) should be registered now. Registered accounts
 * are left alone. After a failure, an [explicit] trigger (the owner turning prompts on, a new sign-in) retries at once;
 * automatic ones, which come with every push reconnect, wait [CHIME_RETRY_AFTER_FAILURE_MILLIS]. A clock that moved
 * backwards does not hold registration back.
 */
fun needsChimeRegistration(stored: ChimeGmsAccount?, now: Long, explicit: Boolean): Boolean {
    if (stored == null) return true
    if (stored.registrationStatus == RegistrationStatus.STATUS_REGISTERED) return false
    if (explicit) return true
    val since = now - stored.updatedAt
    return since < 0 || since >= CHIME_RETRY_AFTER_FAILURE_MILLIS
}
