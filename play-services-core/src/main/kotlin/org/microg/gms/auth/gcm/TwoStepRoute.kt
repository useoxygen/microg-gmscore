/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.gcm

import org.microg.gms.gcm.NotificationData

/** GmsCore push messages for this route carry this `gcmb` body type. */
internal const val GMS_GCM_NOTIFICATIONS = "notifications"

/**
 * Read states sent to GNOTS, as Google Play services 26.22.36 (`GunsIntentOperation`) sends them: shown when the
 * notification is posted, opened when the owner taps it, dismissed when the owner swipes it away or it expires.
 * Upstream microG sent 2 and then 5 on display, which told Google the prompt was already dismissed.
 */
internal const val NOTIFICATION_STATUS_OPENED = 2
internal const val NOTIFICATION_STATUS_SHOWN = 4
internal const val NOTIFICATION_STATUS_DISMISSED = 5

internal const val NOTIFICATION_FETCH_ATTEMPTS = 3
internal const val NOTIFICATION_FETCH_DELAY_MILLIS = 500L

internal enum class ShowResult { SHOWN, NOTIFICATIONS_BLOCKED, NOT_SHOWABLE }

/** A prompt without a usable expiry stays this long; Google's own prompts expire within minutes. */
internal const val PROMPT_DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000L
internal const val PROMPT_MIN_TIMEOUT_MILLIS = 60 * 1000L
internal const val PROMPT_MAX_TIMEOUT_MILLIS = 60 * 60 * 1000L
/** Google Play services 26.22 skips GUNS notifications with 10 s or less left ("Received expired payload"). */
internal const val PROMPT_EXPIRED_MARGIN_MILLIS = 10 * 1000L
private const val PROMPT_PLAUSIBLE_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Milliseconds from [referenceMillis] until GNOTS `expiryTime`, or null if it is missing or implausible (more than a
 * day from the reference either way). Google Play services 26.22.36 reads the field as microseconds since the epoch
 * and measures it against the server time sent with the push, not the phone's clock.
 */
internal fun promptExpiryRemainingMillis(expiryTime: Long?, referenceMillis: Long): Long? {
    if (expiryTime == null || expiryTime <= 0) return null
    return (expiryTime / 1000 - referenceMillis).takeIf { it in -PROMPT_PLAUSIBLE_MILLIS..PROMPT_PLAUSIBLE_MILLIS }
}

/**
 * How long a shown prompt may stay before Android removes it. Without this, a prompt that was never answered stayed
 * until another prompt for the account was completed; Google Play services removes its notifications at the expiry
 * with an alarm. A missing or implausible expiry falls back to [PROMPT_DEFAULT_TIMEOUT_MILLIS] (Google keeps such a
 * notification), and the result is kept within one minute and one hour. Android applies the timeout from Android 8
 * (API 26); on older versions an unanswered prompt stays.
 */
internal fun promptTimeoutMillis(expiryTime: Long?, referenceMillis: Long): Long =
    promptExpiryRemainingMillis(expiryTime, referenceMillis)?.coerceIn(PROMPT_MIN_TIMEOUT_MILLIS, PROMPT_MAX_TIMEOUT_MILLIS)
        ?: PROMPT_DEFAULT_TIMEOUT_MILLIS

/**
 * Whether a prompt is too close to its expiry to show. Only judged against Google's server time ([serverTimeMillis]:
 * the fetch response's server time, else the push's send time); with the phone's own clock, a phone running fast
 * would hide valid prompts.
 */
internal fun promptExpired(expiryTime: Long?, serverTimeMillis: Long?): Boolean {
    if (serverTimeMillis == null) return false
    val remaining = promptExpiryRemainingMillis(expiryTime, serverTimeMillis) ?: return false
    return remaining <= PROMPT_EXPIRED_MARGIN_MILLIS
}

internal enum class MatchFailure { NO_USER_ID, NO_ACCOUNT, AMBIGUOUS }

/** A GNOTS fetch: the notifications and the server's time at the fetch (`NotificationList.serverTime`, in ms). */
internal class GnotsFetch(val notifications: List<NotificationData>, val serverTimeMillis: Long?)

/**
 * The local account whose Google user id is [userId], or why there is none. Two accounts claiming the same id is
 * refused rather than guessed: a prompt must never open under the wrong account.
 */
internal fun <A> selectAccountByUserId(candidates: List<Pair<A, String?>>, userId: String?): Pair<A?, MatchFailure?> {
    if (userId.isNullOrEmpty()) return null to MatchFailure.NO_USER_ID
    val matches = candidates.filter { it.second == userId }
    return when (matches.size) {
        0 -> null to MatchFailure.NO_ACCOUNT
        1 -> matches.single().first to null
        else -> null to MatchFailure.AMBIGUOUS
    }
}

/**
 * Whether GmsCore may record its own push permission as granted without asking: only for GmsCore's own package,
 * only while push is on and the owner's two-step setting is on, and only when microG has no entry yet. An explicit
 * grant or denial in microG's push settings always stands, and turning the two-step setting off stops new grants.
 *
 * DESIGN, a deliberate Cyclon deviation (docs/google-prompt.md): the two-step setting is on by default on Cyclon
 * phones, and the push permission dialog cannot open from the background service that registers GmsCore.
 */
internal fun mayGrantOwnPushPermission(packageName: String, gmsPackageName: String, twoStepOn: Boolean, gcmEnabled: Boolean, knownToMicroG: Boolean): Boolean =
    packageName == gmsPackageName && twoStepOn && gcmEnabled && !knownToMicroG

/**
 * Whether the owner allows this route; if not, a GNOTS message is dropped and recorded. Other GmsCore messages (for
 * example Find My Device ones, which share the receiver) pass silently so the log only shows this route's drops.
 */
internal fun admitTwoStepMessage(prefOn: Boolean, gcmBody: String?, diagnostics: TwoStepDiagnostics): Boolean {
    if (prefOn) return true
    if (gcmBody == GMS_GCM_NOTIFICATIONS) diagnostics.event(TwoStepEvent.PREF_OFF, diagnostics.newSession())
    return false
}

/**
 * Google's existing two-step verification route (upstream microG #2866/#2990): a GNOTS tickle names a notification,
 * which is fetched for the matching account and shown as a notification that opens Google's confirmation page.
 * This class never approves anything; the owner decides on Google's page.
 *
 * Android and network access are passed in so the routing is host-testable: [accounts] lists local accounts with
 * their Google user ids, [fetch] asks GNOTS for the full notifications (an empty list counts as a failed attempt),
 * [setReadState] reports a read state, [show] posts the notification, [decodeBase64] decodes the URL-safe payload.
 */
internal class TwoStepRoute<A>(
    private val diagnostics: TwoStepDiagnostics,
    private val enabled: () -> Boolean,
    private val accounts: () -> List<Pair<A, String?>>,
    private val fetch: (A, NotificationData) -> GnotsFetch,
    private val setReadState: (A, NotificationData, Int) -> Unit,
    private val show: (A, NotificationData, Long) -> ShowResult,
    private val dismiss: (String) -> Int,
    private val decodeBase64: (String) -> ByteArray,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * [pushTimeMillis] is the push's send time from Google's server, if the push carried one. The fetch response's
     * server time replaces it when present, as in Google Play services, so a push delivered late is judged by now.
     */
    fun route(gcmBody: String?, payload: String?, pushTimeMillis: Long? = null) {
        val session = diagnostics.newSession()
        if (gcmBody != GMS_GCM_NOTIFICATIONS) {
            diagnostics.event(TwoStepEvent.IGNORED, session, "has_body" to (gcmBody != null))
            return
        }
        if (!enabled()) {
            diagnostics.event(TwoStepEvent.PREF_OFF, session)
            return
        }
        diagnostics.event(TwoStepEvent.RECEIVED, session, "size" to sizeBucket(payload?.length ?: 0))
        if (payload.isNullOrEmpty()) {
            diagnostics.event(TwoStepEvent.MALFORMED, session, "payload" to false)
            return
        }
        val tickle = try {
            NotificationData.ADAPTER.decode(decodeBase64(payload))
        } catch (e: Exception) {
            diagnostics.event(TwoStepEvent.MALFORMED, session, "payload" to true)
            return
        }
        if (tickle.isActive == true) {
            dismissRemotely(session, tickle, Stage.TICKLE)
            return
        }
        val candidates = accounts()
        val (account, failure) = selectAccountByUserId(candidates, tickle.userInfo?.userId)
        if (account == null) {
            diagnostics.event(TwoStepEvent.ACCOUNT_NOT_MATCHED, session, "reason" to failure, "accounts" to candidates.size)
            return
        }
        val fetched = fetchWithRetries(session, account, tickle) ?: return
        val serverTimeMillis = fetched.serverTimeMillis ?: pushTimeMillis
        diagnostics.event(TwoStepEvent.FETCHED, session, "count" to fetched.notifications.size)
        for (notification in fetched.notifications) {
            // Google Play services passes fetched notifications through the same removal handling as pushes.
            if (notification.isActive == true) {
                dismissRemotely(session, notification, Stage.FETCHED)
                continue
            }
            if (promptExpired(notification.expiryTime, serverTimeMillis)) {
                diagnostics.event(TwoStepEvent.EXPIRED, session)
                continue
            }
            val reference = serverTimeMillis ?: now()
            val timeoutMillis = promptTimeoutMillis(notification.expiryTime, reference)
            when (show(account, notification, timeoutMillis)) {
                ShowResult.SHOWN -> {
                    // Whole minutes until the notification times out, so a device test can see the expiry was used.
                    // `expiry` says whether Google's expiry was usable or the default timeout applied.
                    val expiryUsed = promptExpiryRemainingMillis(notification.expiryTime, reference) != null
                    diagnostics.event(TwoStepEvent.SHOWN, session, "timeout_min" to timeoutMillis / 60_000, "expiry" to expiryUsed, "server_time" to (serverTimeMillis != null))
                    reportReadState(session, account, notification, NOTIFICATION_STATUS_SHOWN)
                }
                ShowResult.NOTIFICATIONS_BLOCKED -> diagnostics.event(TwoStepEvent.NOTIFICATIONS_BLOCKED, session)
                ShowResult.NOT_SHOWABLE -> diagnostics.event(TwoStepEvent.NOT_SHOWABLE, session, "content" to (notification.content != null))
            }
        }
    }

    /**
     * Field 3 (`isActive` in microG's proto) asks the device to take the notification down, for example after the prompt
     * was answered on another device. Google Play services removes it and logs NOTIFICATION_DISMISSED_REMOTE.
     */
    private fun dismissRemotely(session: String, notification: NotificationData, stage: Stage) {
        val removed = notification.identifier?.uniqueId?.takeIf { it.isNotEmpty() }?.let(dismiss) ?: 0
        diagnostics.event(TwoStepEvent.DISMISSED_REMOTE, session, "stage" to stage, "count" to removed)
    }

    private fun fetchWithRetries(session: String, account: A, tickle: NotificationData): GnotsFetch? {
        for (attempt in 1..NOTIFICATION_FETCH_ATTEMPTS) {
            try {
                val fetched = fetch(account, tickle)
                if (fetched.notifications.isNotEmpty()) return fetched
                diagnostics.event(TwoStepEvent.FETCH_FAILED, session, "attempt" to attempt, "reason" to FetchFailure.EMPTY)
            } catch (e: Exception) {
                diagnostics.event(TwoStepEvent.FETCH_FAILED, session, "attempt" to attempt, "reason" to FetchFailure.ERROR)
            }
            if (attempt < NOTIFICATION_FETCH_ATTEMPTS) sleep(NOTIFICATION_FETCH_DELAY_MILLIS)
        }
        diagnostics.event(TwoStepEvent.FETCH_FAILED, session, "final" to true)
        return null
    }

    private fun reportReadState(session: String, account: A, notification: NotificationData, status: Int) {
        try {
            setReadState(account, notification, status)
        } catch (e: Exception) {
            diagnostics.event(TwoStepEvent.READ_STATE_FAILED, session, "status" to status)
        }
    }

    private enum class Stage { TICKLE, FETCHED }
    private enum class FetchFailure { EMPTY, ERROR }
}
