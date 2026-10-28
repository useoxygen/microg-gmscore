/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.gcm

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.gcm.IntentActions
import org.microg.gms.gcm.IntentExtra
import org.microg.gms.gcm.IntentPayload
import org.microg.gms.gcm.NotificationButton
import org.microg.gms.gcm.NotificationContent
import org.microg.gms.gcm.NotificationData
import org.microg.gms.gcm.NotificationIdentifier
import org.microg.gms.gcm.UserInfo
import java.util.Base64
import kotlin.random.Random

class TwoStepRouteTest {
    private val ownerEmail = "owner.secret@example.com"
    private val otherEmail = "other.secret@example.com"
    private val ownerUserId = "109876543210987654321"
    private val otherUserId = "108765432109876543210"
    private val uniqueId = "gnots-unique-id-secret"
    private val description = "Is it you trying to sign in? owner-secret-description"
    private val pageExtra = "https://accounts.google.com/secret-capability"

    private val logs = mutableListOf<String>()
    private val diagnostics = TwoStepDiagnostics(Random(42)) { logs += it }

    private var enabled = true
    private var accounts = listOf(otherEmail to otherUserId, ownerEmail to ownerUserId)
    private var fetchResults = mutableListOf<() -> List<NotificationData>>()
    private var showResult = ShowResult.SHOWN
    private val fetchedFor = mutableListOf<String>()
    private val shownFor = mutableListOf<String>()
    private val readStates = mutableListOf<Pair<String, Int>>()
    private val dismissed = mutableListOf<String>()
    private val sleeps = mutableListOf<Long>()

    private val route = TwoStepRoute<String>(
        diagnostics = diagnostics,
        enabled = { enabled },
        accounts = { accounts },
        fetch = { account, _ -> fetchedFor += account; GnotsFetch(fetchResults.removeAt(0)(), null) },
        setReadState = { account, _, status -> readStates += account to status },
        show = { account, _, _ -> shownFor += account; ShowOutcome(showResult, fullScreen = showResult == ShowResult.SHOWN) },
        dismiss = { id -> dismissed += id; 1 },
        decodeBase64 = { Base64.getUrlDecoder().decode(it) },
        sleep = { sleeps += it },
    )

    private fun tickle(userId: String? = ownerUserId, active: Boolean? = null) = NotificationData(
        userInfo = userId?.let { UserInfo(userId = it) },
        identifier = NotificationIdentifier(uniqueId = uniqueId),
        isActive = active,
    ).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encode()) }

    private val fetched = NotificationData(
        userInfo = UserInfo(userId = ownerUserId),
        identifier = NotificationIdentifier(uniqueId = uniqueId),
        content = NotificationContent(accountName = ownerEmail, description = description, actionButtons = listOf(NotificationButton(icon = "approve"))),
        intentActions = IntentActions(primaryPayload = IntentPayload(extras = listOf(IntentExtra(key = "extra.url", value_ = pageExtra)))),
    )

    private fun events() = logs.map { it.substringAfter("event=").substringBefore(' ') }

    @Test
    fun syntheticPayloadIsFetchedForTheMatchingAccountAndShown() {
        fetchResults += { listOf(fetched) }
        route.route(GMS_GCM_NOTIFICATIONS, tickle())

        assertEquals(listOf(ownerEmail), fetchedFor)
        assertEquals(listOf(ownerEmail), shownFor)
        // Like Google Play services: one "shown" report; upstream's 2 then 5 told Google the prompt was dismissed.
        assertEquals(listOf(ownerEmail to NOTIFICATION_STATUS_SHOWN), readStates)
        assertEquals(listOf("received", "matched", "fetched", "shown"), events())
        val session = logs.first().substringAfter("session=").substringBefore(' ')
        assertTrue(logs.all { it.contains("session=$session") })
    }

    @Test
    fun otherAccountOfTwoIsRoutedByUserId() {
        fetchResults += { listOf(fetched) }
        route.route(GMS_GCM_NOTIFICATIONS, tickle(userId = otherUserId))
        assertEquals(listOf(otherEmail), fetchedFor)
        assertEquals(listOf(otherEmail), shownFor)
    }

    @Test
    fun prefOffDropsTheMessageBeforeAnyNetworkWork() {
        enabled = false
        route.route(GMS_GCM_NOTIFICATIONS, tickle())
        assertTrue(fetchedFor.isEmpty())
        assertTrue(shownFor.isEmpty())
        assertEquals(listOf("pref_off"), events())
    }

    @Test
    fun receiverGateDropsOnlyWhenOffAndRecordsOnlyThisRoute() {
        assertTrue(admitTwoStepMessage(true, GMS_GCM_NOTIFICATIONS, diagnostics))
        assertTrue(logs.isEmpty())
        assertFalse(admitTwoStepMessage(false, "findmydevice", diagnostics))
        assertTrue(logs.isEmpty())
        assertFalse(admitTwoStepMessage(false, GMS_GCM_NOTIFICATIONS, diagnostics))
        assertEquals(listOf("pref_off"), events())
    }

    @Test
    fun otherGmsCoreMessagesAreIgnored() {
        route.route(null, null)
        route.route("findmydevice", tickle())
        assertTrue(fetchedFor.isEmpty())
        assertEquals(listOf("ignored", "ignored"), events())
    }

    @Test
    fun unmatchedMissingOrAmbiguousUserIdsAreNotFetched() {
        route.route(GMS_GCM_NOTIFICATIONS, tickle(userId = "107000000000000000000"))
        route.route(GMS_GCM_NOTIFICATIONS, tickle(userId = null))
        accounts = listOf(ownerEmail to ownerUserId, otherEmail to ownerUserId)
        route.route(GMS_GCM_NOTIFICATIONS, tickle())

        assertTrue(fetchedFor.isEmpty())
        val reasons = logs.filter { it.startsWith("event=account_not_matched") }.map { it.substringAfter("reason=").substringBefore(' ') }
        assertEquals(listOf("no_account", "no_user_id", "ambiguous"), reasons)
    }

    @Test
    fun accountSelectionNeedsExactlyOneMatch() {
        val candidates = listOf("a" to "1", "b" to null, "c" to "3")
        assertEquals("c" to null, selectAccountByUserId(candidates, "3"))
        assertEquals(null to MatchFailure.NO_ACCOUNT, selectAccountByUserId(candidates, "2"))
        assertEquals(null to MatchFailure.NO_USER_ID, selectAccountByUserId(candidates, ""))
        assertEquals(null to MatchFailure.AMBIGUOUS, selectAccountByUserId(candidates + ("d" to "3"), "3"))
    }

    @Test
    fun fetchIsRetriedThreeTimesThenRecordedAsFailed() {
        repeat(3) { fetchResults += { throw java.io.IOException("token ya29.secret") } }
        route.route(GMS_GCM_NOTIFICATIONS, tickle())

        assertEquals(3, fetchedFor.size)
        assertEquals(listOf(NOTIFICATION_FETCH_DELAY_MILLIS, NOTIFICATION_FETCH_DELAY_MILLIS), sleeps)
        assertTrue(shownFor.isEmpty())
        assertEquals(listOf("received", "matched", "fetch_failed", "fetch_failed", "fetch_failed", "fetch_failed"), events())
        assertTrue(logs.last().contains("final=true"))
        assertFalse(logs.any { it.contains("ya29") })
    }

    @Test
    fun emptyFetchCountsAsAFailedAttempt() {
        fetchResults += { emptyList() }
        fetchResults += { listOf(fetched) }
        route.route(GMS_GCM_NOTIFICATIONS, tickle())
        assertEquals(2, fetchedFor.size)
        assertEquals(listOf("received", "matched", "fetch_failed", "fetched", "shown"), events())
    }

    @Test
    fun blockedNotificationsAreRecordedAndNoReadStateIsClaimed() {
        fetchResults += { listOf(fetched) }
        showResult = ShowResult.NOTIFICATIONS_BLOCKED
        route.route(GMS_GCM_NOTIFICATIONS, tickle())
        assertTrue(readStates.isEmpty())
        assertEquals(listOf("received", "matched", "fetched", "notifications_blocked"), events())
    }

    @Test
    fun aRemovalTickleTakesTheShownPromptDown() {
        route.route(GMS_GCM_NOTIFICATIONS, tickle(active = true))

        assertEquals(listOf(uniqueId), dismissed)
        assertTrue(fetchedFor.isEmpty())
        assertEquals(listOf("received", "dismissed_remote"), events())
        assertTrue(logs.last().endsWith("count=1"))
    }

    @Test
    fun fetchedNotificationsFlaggedForRemovalTakeTheShownPromptDown() {
        fetchResults += { listOf(fetched.copy(isActive = true)) }
        route.route(GMS_GCM_NOTIFICATIONS, tickle())

        assertTrue(shownFor.isEmpty())
        assertEquals(listOf(uniqueId), dismissed)
        assertTrue(logs.last(), logs.last().contains("event=dismissed_remote") && logs.last().contains("stage=fetched"))
    }

    @Test
    fun malformedPayloadsAreRecorded() {
        route.route(GMS_GCM_NOTIFICATIONS, null)
        route.route(GMS_GCM_NOTIFICATIONS, "!!not base64!!")
        assertTrue(fetchedFor.isEmpty())
        assertEquals(listOf("received", "malformed", "received", "malformed"), events())
    }

    @Test
    fun logsCarryNoAccountIdsTokensOrContent() {
        fetchResults += { listOf(fetched) }
        route.route(GMS_GCM_NOTIFICATIONS, tickle())
        repeat(3) { fetchResults += { throw IllegalStateException("$ownerEmail $uniqueId") } }
        route.route(GMS_GCM_NOTIFICATIONS, tickle(userId = otherUserId))
        route.route(GMS_GCM_NOTIFICATIONS, tickle(userId = "107000000000000000000"))

        val secrets = listOf(ownerEmail, otherEmail, ownerUserId, otherUserId, "107000000000000000000", uniqueId, description, pageExtra, "owner-secret", "approve")
        assertTrue(logs.isNotEmpty())
        for (line in logs) for (secret in secrets) assertFalse("$secret in $line", line.contains(secret))
    }

    @Test
    fun diagnosticsRenderOnlyNumbersBooleansAndEnums() {
        diagnostics.event(TwoStepEvent.SHOWN, "s", "account" to ownerEmail, "count" to 2, "ok" to true, "reason" to MatchFailure.AMBIGUOUS, "none" to null)
        assertEquals("event=shown session=s account=? count=2 ok=true reason=ambiguous none=none", logs.single())
    }

    @Test
    fun readinessReportsNotRegisteredOnlyWhenOnAndBlocked() {
        val ready = TwoStepReadiness(true, true, SelfRegistration.ALLOWED, true, 2, 2, true, true)
        ready.log(diagnostics)
        assertEquals(listOf("readiness"), events())
        assertEquals("event=readiness pref=true gcm=true self_registration=allowed gms_registered=true accounts=2 chime_registered=2 notifications=true channel=true", logs.single())

        logs.clear()
        ready.copy(chimeRegisteredAccounts = 0).log(diagnostics)
        ready.copy(selfRegistration = SelfRegistration.DENIED).log(diagnostics)
        ready.copy(gmsRegistered = false).log(diagnostics)
        assertEquals(listOf("readiness", "not_registered", "readiness", "not_registered", "readiness", "not_registered"), events())

        logs.clear()
        ready.copy(prefOn = false, gmsRegistered = false).log(diagnostics)
        ready.copy(notificationsAllowed = false, channelAllowed = null).log(diagnostics)
        assertEquals(listOf("readiness", "readiness"), events())
    }

    @Test
    fun readStatesFollowGooglePlayServices() {
        for (status in listOf(NOTIFICATION_STATUS_SHOWN, NOTIFICATION_STATUS_OPENED, NOTIFICATION_STATUS_DISMISSED)) {
            val item = readStateItems(fetched.identifier, status)!!.single()
            assertEquals(status, item.status)
            assertEquals(uniqueId, item.notification?.uniqueId)
            assertNull(item.state)
        }
        assertEquals(listOf(4, 2, 5), listOf(NOTIFICATION_STATUS_SHOWN, NOTIFICATION_STATUS_OPENED, NOTIFICATION_STATUS_DISMISSED))
        assertNull(readStateItems(NotificationIdentifier(uniqueId = ""), NOTIFICATION_STATUS_SHOWN))
        assertNull(readStateItems(null, NOTIFICATION_STATUS_SHOWN))
    }

    @Test
    fun gmsCoreGrantsItsOwnPushPermissionOnlyWhileTwoStepIsOnAndMicroGHasNotDecided() {
        val gms = "com.google.android.gms"
        assertTrue(mayGrantOwnPushPermission(gms, gms, twoStepOn = true, gcmEnabled = true, knownToMicroG = false))
        // Another package, the owner's opt-out, push off, or any decision already in microG's push settings: no grant.
        assertFalse(mayGrantOwnPushPermission("com.example.app", gms, twoStepOn = true, gcmEnabled = true, knownToMicroG = false))
        assertFalse(mayGrantOwnPushPermission(gms, gms, twoStepOn = false, gcmEnabled = true, knownToMicroG = false))
        assertFalse(mayGrantOwnPushPermission(gms, gms, twoStepOn = true, gcmEnabled = false, knownToMicroG = false))
        assertFalse(mayGrantOwnPushPermission(gms, gms, twoStepOn = true, gcmEnabled = true, knownToMicroG = true))
    }

    @Test
    fun promptsTimeOutAtGooglesExpiry() {
        val now = 1_792_800_000_000L
        val inFiveMinutesUs = (now + 5 * 60_000) * 1000
        // Microseconds, as Google Play services reads the field.
        assertEquals(5 * 60_000L, promptTimeoutMillis(inFiveMinutesUs, now))
        // Missing or implausible values (wrong unit, decades off, a month ahead) fall back to the default.
        for (expiry in listOf(null, 0L, 1L, (now + 5 * 60_000) / 1000, now + 5 * 60_000, (now + 30L * 24 * 3600_000) * 1000)) {
            assertEquals("$expiry", PROMPT_DEFAULT_TIMEOUT_MILLIS, promptTimeoutMillis(expiry, now))
        }
        // Recently expired or far but plausible values are clamped.
        assertEquals(PROMPT_MIN_TIMEOUT_MILLIS, promptTimeoutMillis((now - 60_000) * 1000, now))
        assertEquals(PROMPT_MAX_TIMEOUT_MILLIS, promptTimeoutMillis((now + 5 * 3600_000L) * 1000, now))
        assertNull(promptExpiryRemainingMillis(1, now))
        assertEquals(-60_000L, promptExpiryRemainingMillis((now - 60_000) * 1000, now))
    }

    @Test
    fun onlyGooglesServerTimeDecidesThatAPromptExpired() {
        val server = 1_792_800_000_000L
        assertTrue(promptExpired((server + 5_000) * 1000, server))
        assertTrue(promptExpired((server + 10_000) * 1000, server))
        assertFalse(promptExpired((server + 10_001) * 1000, server))
        // Without server time nothing is skipped, and an implausible expiry is never "expired".
        assertFalse(promptExpired((server - 60_000) * 1000, null))
        assertFalse(promptExpired(1, server))
        assertFalse(promptExpired(null, server))
    }

    private fun routeWithClock(now: Long, expiryUs: Long?, timeouts: MutableList<Long>, fetchServerTime: Long? = null) = TwoStepRoute<String>(
        diagnostics = diagnostics,
        enabled = { true },
        accounts = { accounts },
        fetch = { _, _ -> GnotsFetch(listOf(fetched.copy(expiryTime = expiryUs)), fetchServerTime) },
        setReadState = { _, _, _ -> },
        show = { _, _, timeout -> timeouts += timeout; ShowOutcome(ShowResult.SHOWN) },
        dismiss = { 0 },
        decodeBase64 = { Base64.getUrlDecoder().decode(it) },
        now = { now },
    )

    @Test
    fun theShownEventRecordsTheTimeoutInMinutes() {
        val server = 1_792_800_000_000L
        val timeouts = mutableListOf<Long>()
        routeWithClock(server, (server + 3 * 60_000) * 1000, timeouts).route(GMS_GCM_NOTIFICATIONS, tickle(), server)
        assertEquals(listOf(3 * 60_000L), timeouts)
        assertTrue(logs.last(), logs.last().endsWith("timeout_min=3 expiry=true server_time=true full_screen=false opened_now=false"))
    }

    @Test
    fun aPhoneClockRunningAheadNeitherHidesNorShortensAPrompt() {
        val server = 1_792_800_000_000L
        val timeouts = mutableListOf<Long>()
        // Five minutes of real validity, phone six minutes ahead: shown, and timed out after five minutes.
        routeWithClock(server + 6 * 60_000, (server + 5 * 60_000) * 1000, timeouts).route(GMS_GCM_NOTIFICATIONS, tickle(), server)
        assertEquals(listOf(5 * 60_000L), timeouts)
        assertEquals("shown", events().last())
    }

    @Test
    fun aLateDeliveredPushIsJudgedByTheFetchsServerTime() {
        val sent = 1_792_800_000_000L
        val fetchedAt = sent + 4 * 60_000 + 55_000
        val timeouts = mutableListOf<Long>()
        // Valid for five minutes when sent, delivered 4m55s later: the fetch's server time says 5 s are left.
        routeWithClock(fetchedAt, (sent + 5 * 60_000) * 1000, timeouts, fetchServerTime = fetchedAt).route(GMS_GCM_NOTIFICATIONS, tickle(), sent)
        assertTrue(timeouts.isEmpty())
        assertEquals("expired", events().last())
        // Two minutes late: shown, timing out after the three minutes left.
        logs.clear()
        routeWithClock(sent + 2 * 60_000, (sent + 5 * 60_000) * 1000, timeouts, fetchServerTime = sent + 2 * 60_000).route(GMS_GCM_NOTIFICATIONS, tickle(), sent)
        assertEquals(listOf(3 * 60_000L), timeouts)
    }

    @Test
    fun promptsAboutToExpireAreNotShown() {
        val server = 1_792_800_000_000L
        val timeouts = mutableListOf<Long>()
        routeWithClock(server, (server + 5_000) * 1000, timeouts).route(GMS_GCM_NOTIFICATIONS, tickle(), server)
        assertTrue(timeouts.isEmpty())
        assertEquals(listOf("received", "matched", "fetched", "expired"), events())
    }

    @Test
    fun sizeBucketsHidePreciseLengths() {
        assertEquals(listOf(0, 256, 256, 1024, 4096, 65536), listOf(0, 1, 256, 257, 4000, 100_000).map(::sizeBucket))
    }
}
