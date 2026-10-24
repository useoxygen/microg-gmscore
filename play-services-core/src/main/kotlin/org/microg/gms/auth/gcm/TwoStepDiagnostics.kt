/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.gcm

import java.util.Locale
import kotlin.random.Random

/**
 * Stages of Google's two-step verification route (GNOTS push → authenticated fetch → notification → Google's web page).
 * A device test reads these to tell "nothing was delivered" from "delivered and then dropped" (useoxygen/cyclon#842).
 */
internal enum class TwoStepEvent {
    /** The owner turned the route off; a GNOTS message was discarded. */
    PREF_OFF,
    /** Readiness says pushes cannot reach this route yet (no GmsCore registration, no registered account, ...). */
    NOT_REGISTERED,
    /** Snapshot of the route's preconditions, booleans and counts only. */
    READINESS,
    /** A GNOTS message for this route arrived. */
    RECEIVED,
    /** A GmsCore message that is not a GNOTS notification (for example Find My Device); not for this route. */
    IGNORED,
    /** The GNOTS payload was missing or could not be decoded. */
    MALFORMED,
    /** The notification is flagged active; this route does not show those (behaviour inherited from upstream). */
    ALREADY_ACTIVE,
    /** No single local account matches the notification's Google user id. */
    ACCOUNT_NOT_MATCHED,
    FETCH_FAILED,
    FETCHED,
    /** The fetched notification lacks the content or the confirmation page's intent. */
    NOT_SHOWABLE,
    /** Google's expiry leaves too little time to answer, so the prompt is not shown (as Google Play services does). */
    EXPIRED,
    /** The owner blocked this app's notifications or the prompt channel. */
    NOTIFICATIONS_BLOCKED,
    SHOWN,
    /** Reporting a read state to GNOTS failed; the notification itself was shown. */
    READ_STATE_FAILED,
    /** Google's confirmation page closed and the shown notifications were completed. */
    COMPLETED,
    /** GmsCore's own push message could not be handed to the route. */
    DISPATCH_DROPPED,
}

/**
 * Structured, redacted log of the two-step route. Lines look like `event=fetched session=1a2b3c4d count=1`.
 *
 * Only numbers, booleans and enum constants are written as details; anything else becomes `?`, so account names,
 * Google user ids, tokens, notification text and payloads cannot reach the log through this class. The session is a
 * random local id that ties together the events of one incoming message; it is derived from nothing Google sent.
 */
internal class TwoStepDiagnostics(private val random: Random = Random.Default, private val sink: (String) -> Unit) {
    fun newSession(): String = String.format(Locale.ROOT, "%08x", random.nextInt())

    fun event(event: TwoStepEvent, session: String? = null, vararg details: Pair<String, Any?>) {
        val line = StringBuilder("event=").append(event.name.lowercase(Locale.ROOT))
        if (session != null) line.append(" session=").append(session)
        for ((key, value) in details) {
            line.append(' ').append(key).append('=').append(render(value))
        }
        sink(line.toString())
    }

    private fun render(value: Any?): String = when (value) {
        null -> "none"
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Enum<*> -> value.name.lowercase(Locale.ROOT)
        else -> "?"
    }
}

/** Rough size of a payload, so logs can show its shape without its length to the byte. */
internal fun sizeBucket(length: Int): Int = when {
    length <= 0 -> 0
    length <= 256 -> 256
    length <= 1024 -> 1024
    length <= 4096 -> 4096
    else -> 65536
}

internal enum class SelfRegistration { ALLOWED, NOT_YET_KNOWN, DENIED }

/**
 * Preconditions of the two-step route, as seen from GmsCore's persistent process. Booleans and counts only.
 *
 * [selfRegistration] is GmsCore's own entry in microG's push permission list; [gmsRegistered] means GmsCore holds a
 * registration for Google's notification sender; [chimeRegisteredAccounts] counts accounts whose notification
 * registration (MultiLoginUpdate) succeeded. Notification state is `null` where Android cannot tell.
 */
internal data class TwoStepReadiness(
    val prefOn: Boolean,
    val gcmEnabled: Boolean,
    val selfRegistration: SelfRegistration,
    val gmsRegistered: Boolean,
    val accounts: Int,
    val chimeRegisteredAccounts: Int,
    val notificationsAllowed: Boolean,
    val channelAllowed: Boolean?,
) {
    /** Whether pushes can reach the route at all; notification blocking is reported separately when a prompt arrives. */
    val registered: Boolean
        get() = prefOn && gcmEnabled && selfRegistration != SelfRegistration.DENIED && gmsRegistered && chimeRegisteredAccounts > 0

    fun log(diagnostics: TwoStepDiagnostics) {
        val details = arrayOf<Pair<String, Any?>>(
            "pref" to prefOn,
            "gcm" to gcmEnabled,
            "self_registration" to selfRegistration,
            "gms_registered" to gmsRegistered,
            "accounts" to accounts,
            "chime_registered" to chimeRegisteredAccounts,
            "notifications" to notificationsAllowed,
            "channel" to channelAllowed,
        )
        diagnostics.event(TwoStepEvent.READINESS, null, *details)
        if (prefOn && !registered) diagnostics.event(TwoStepEvent.NOT_REGISTERED, null, *details)
    }
}
