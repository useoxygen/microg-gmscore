/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.gcm

import org.microg.gms.gcm.NotificationData

/** GmsCore push messages for this route carry this `gcmb` body type. */
internal const val GMS_GCM_NOTIFICATIONS = "notifications"

/** Read states sent to GNOTS. The names are microG's reverse-engineered labels, not documented Google semantics. */
internal const val NOTIFICATION_STATUS_READY = 2
internal const val NOTIFICATION_STATUS_COMPLETE = 5

internal const val NOTIFICATION_FETCH_ATTEMPTS = 3
internal const val NOTIFICATION_FETCH_DELAY_MILLIS = 500L

internal enum class ShowResult { SHOWN, NOTIFICATIONS_BLOCKED, NOT_SHOWABLE }

internal enum class MatchFailure { NO_USER_ID, NO_ACCOUNT, AMBIGUOUS }

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
    private val fetch: (A, NotificationData) -> List<NotificationData>,
    private val setReadState: (A, NotificationData, Int) -> Unit,
    private val show: (A, NotificationData) -> ShowResult,
    private val decodeBase64: (String) -> ByteArray,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun route(gcmBody: String?, payload: String?) {
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
        // Upstream skips notifications flagged active, at the tickle and again after the fetch. What the flag means
        // is not established (report §4), so this keeps the behaviour and records every skip for the device test.
        if (tickle.isActive == true) {
            diagnostics.event(TwoStepEvent.ALREADY_ACTIVE, session, "stage" to Stage.TICKLE)
            return
        }
        val candidates = accounts()
        val (account, failure) = selectAccountByUserId(candidates, tickle.userInfo?.userId)
        if (account == null) {
            diagnostics.event(TwoStepEvent.ACCOUNT_NOT_MATCHED, session, "reason" to failure, "accounts" to candidates.size)
            return
        }
        val notifications = fetchWithRetries(session, account, tickle) ?: return
        diagnostics.event(TwoStepEvent.FETCHED, session, "count" to notifications.size)
        for (notification in notifications) {
            if (notification.isActive == true) {
                diagnostics.event(TwoStepEvent.ALREADY_ACTIVE, session, "stage" to Stage.FETCHED)
                continue
            }
            when (show(account, notification)) {
                ShowResult.SHOWN -> {
                    diagnostics.event(TwoStepEvent.SHOWN, session)
                    // Upstream reports READY then COMPLETE right after display; COMPLETE is sent again when Google's
                    // page closes. Kept as is: the statuses' server meaning is not established (report §4).
                    reportReadState(session, account, notification, NOTIFICATION_STATUS_READY)
                    reportReadState(session, account, notification, NOTIFICATION_STATUS_COMPLETE)
                }
                ShowResult.NOTIFICATIONS_BLOCKED -> diagnostics.event(TwoStepEvent.NOTIFICATIONS_BLOCKED, session)
                ShowResult.NOT_SHOWABLE -> diagnostics.event(TwoStepEvent.NOT_SHOWABLE, session, "content" to (notification.content != null))
            }
        }
    }

    private fun fetchWithRetries(session: String, account: A, tickle: NotificationData): List<NotificationData>? {
        for (attempt in 1..NOTIFICATION_FETCH_ATTEMPTS) {
            try {
                val notifications = fetch(account, tickle)
                if (notifications.isNotEmpty()) return notifications
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
