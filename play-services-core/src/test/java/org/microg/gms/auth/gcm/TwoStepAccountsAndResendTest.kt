/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.gcm

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.microg.gms.gcm.IntentActions
import org.microg.gms.gcm.IntentExtra
import org.microg.gms.gcm.IntentPayload
import org.microg.gms.gcm.NotificationContent
import org.microg.gms.gcm.NotificationData
import org.microg.gms.gcm.NotificationIdentifier
import org.microg.gms.gcm.UserInfo
import java.io.File
import java.util.Base64
import kotlin.random.Random

/**
 * Phones with several Google accounts (useoxygen/cyclon#842: the founders' phone has five, among them a university and
 * a Workspace account), Google's "Resend it", and how a prompt is presented.
 */
class TwoStepAccountsAndResendTest {
    @get:Rule
    val folder = TemporaryFolder()

    // Local accounts in AccountManager order; the prompt's account is neither the first nor the default.
    private val personal = "first.personal@gmail.com" to "100000000000000000001"
    private val second = "second.personal@gmail.com" to "100000000000000000002"
    private val university = "student@stanford.edu" to "100000000000000000003"
    private val workspace = "founder@workspace-domain.example" to "100000000000000000004"
    private val owner = "owner.real@gmail.com" to "100000000000000000005"
    private val fiveAccounts = listOf(personal, second, university, workspace, owner)

    private val logs = mutableListOf<String>()
    private val diagnostics = TwoStepDiagnostics(Random(7)) { logs += it }
    private var accounts: List<Pair<String, String?>> = fiveAccounts

    private val fetchedFor = mutableListOf<Pair<String, String?>>()
    private val shown = mutableListOf<Pair<String, String?>>()
    private val readStates = mutableListOf<Triple<String, String?, Int>>()
    private val dismissed = mutableListOf<String>()
    private val serverPrompts = mutableMapOf<String, NotificationData>()

    private val route = TwoStepRoute<String>(
        diagnostics = diagnostics,
        enabled = { true },
        accounts = { accounts },
        fetch = { account, tickle ->
            val id = tickle.identifier?.uniqueId
            fetchedFor += account to id
            GnotsFetch(listOfNotNull(serverPrompts[id]), null)
        },
        setReadState = { account, notification, status -> readStates += Triple(account, notification.identifier?.uniqueId, status) },
        show = { account, notification, _ -> shown += account to notification.identifier?.uniqueId; ShowOutcome(ShowResult.SHOWN, fullScreen = true, openedNow = true) },
        dismiss = { id -> dismissed += id; 1 },
        decodeBase64 = { Base64.getUrlDecoder().decode(it) },
        sleep = { },
    )

    /** Google's prompt for [account] with GNOTS unique id [uniqueId], as the fetch returns it. */
    private fun serverPrompt(account: Pair<String, String>, uniqueId: String) = NotificationData(
        userInfo = UserInfo(userId = account.second),
        identifier = NotificationIdentifier(uniqueId = uniqueId),
        content = NotificationContent(accountName = account.first, description = "Is it you trying to sign in?"),
        intentActions = IntentActions(primaryPayload = IntentPayload(extras = listOf(IntentExtra(key = "extra.url", value_ = "https://accounts.google.com/x")))),
    ).also { serverPrompts[uniqueId] = it }

    private fun tickle(userId: String?, uniqueId: String, removal: Boolean? = null) = NotificationData(
        userInfo = userId?.let { UserInfo(userId = it) },
        identifier = NotificationIdentifier(uniqueId = uniqueId),
        isActive = removal,
    ).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encode()) }

    private fun events() = logs.map { it.substringAfter("event=").substringBefore(' ') }

    @Test
    fun aPromptForTheFifthOfFiveAccountsIsFetchedShownAndReportedForThatAccountOnly() {
        serverPrompt(owner, "u1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "u1"))

        assertEquals(listOf(owner.first to "u1"), fetchedFor)
        assertEquals(listOf(owner.first to "u1"), shown)
        assertEquals(listOf(Triple(owner.first, "u1", NOTIFICATION_STATUS_SHOWN)), readStates)
        assertEquals(listOf("received", "matched", "fetched", "shown"), events())
        assertTrue(logs[1], logs[1].endsWith("index=4 accounts=5"))
    }

    @Test
    fun workspaceAndUniversityAccountsAreRoutedByGoogleUserIdLikeAnyOther() {
        for ((account, id) in listOf(workspace to "w1", university to "s1", personal to "p1")) {
            serverPrompt(account, id)
            route.route(GMS_GCM_NOTIFICATIONS, tickle(account.second, id))
        }
        assertEquals(listOf(workspace.first to "w1", university.first to "s1", personal.first to "p1"), shown)
        assertEquals(listOf(workspace.first, university.first, personal.first), readStates.map { it.first })
        assertEquals(listOf("index=3", "index=2", "index=0"), logs.filter { it.startsWith("event=matched") }.map { it.substringAfter("index=").let { v -> "index=" + v.substringBefore(' ') } })
    }

    @Test
    fun theAccountOrderOnThePhoneDoesNotMatter() {
        accounts = fiveAccounts.reversed()
        serverPrompt(owner, "u1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "u1"))
        assertEquals(listOf(owner.first to "u1"), shown)
        assertTrue(logs[1].endsWith("index=0 accounts=5"))
    }

    @Test
    fun anAccountWithoutAStoredUserIdCannotReceivePromptsAndTheLogSaysSo() {
        // The owner's account lost its stored Google user id: the prompt is dropped, never shown under another account.
        accounts = fiveAccounts.map { if (it == owner) owner.first to null else it }
        serverPrompt(owner, "u1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "u1"))

        assertTrue(fetchedFor.isEmpty())
        assertTrue(shown.isEmpty())
        assertEquals("event=account_not_matched", logs.last().substringBefore(" session="))
        assertTrue(logs.last(), logs.last().endsWith("reason=no_account accounts=5 without_id=1"))
    }

    @Test
    fun eachResendIsShownAsANewPrompt() {
        // Seen on the dev Pixel 7a on 2026-10-28: every "Resend it" is a new push naming a new GNOTS unique id.
        for (id in listOf("first", "resend-1", "resend-2")) {
            serverPrompt(owner, id)
            route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, id))
        }
        assertEquals(listOf("first", "resend-1", "resend-2").map { owner.first to it }, shown)
        assertEquals(listOf("first", "resend-1", "resend-2").map { Triple(owner.first, it, NOTIFICATION_STATUS_SHOWN) }, readStates)
        assertTrue(dismissed.isEmpty())
        assertEquals(3, logs.count { it.startsWith("event=shown") })
        assertTrue(logs.filter { it.startsWith("event=shown") }.all { it.endsWith("full_screen=true opened_now=true") })
    }

    @Test
    fun aResendNamingTheSameUniqueIdIsShownAgainNotDeduplicated() {
        serverPrompt(owner, "same")
        repeat(2) { route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "same")) }
        assertEquals(2, shown.size)
    }

    @Test
    fun removingTheFirstPromptLeavesTheResentOneAlone() {
        serverPrompt(owner, "first")
        serverPrompt(owner, "resend-1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "first"))
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "resend-1"))
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "first", removal = true))
        assertEquals(listOf("first"), dismissed)
        assertEquals(2, shown.size)
    }

    @Test
    fun promptsForTwoAccountsInARowEachGoToTheirOwnAccount() {
        serverPrompt(workspace, "w1")
        serverPrompt(owner, "u1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(workspace.second, "w1"))
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "u1"))
        assertEquals(listOf(workspace.first to "w1", owner.first to "u1"), fetchedFor)
        assertEquals(listOf(workspace.first to "w1", owner.first to "u1"), shown)
        assertEquals(listOf(workspace.first to "w1", owner.first to "u1"), readStates.map { it.first to it.second })
    }

    @Test
    fun logsNameNoAccountOfMany() {
        serverPrompt(owner, "u1")
        route.route(GMS_GCM_NOTIFICATIONS, tickle(owner.second, "u1"))
        route.route(GMS_GCM_NOTIFICATIONS, tickle("199999999999999999999", "u2"))
        val secrets = fiveAccounts.flatMap { listOf(it.first, it.second) } + listOf("u1", "u2", "199999999999999999999", "stanford", "workspace")
        for (line in logs) for (secret in secrets) assertFalse("$secret in $line", line.contains(secret))
    }

    @Test
    fun presentationOpensThePageDirectlyOnlyOnAnUnlockedScreenInUse() {
        // In use and unlocked: open now, and keep the full-screen intent so the heads-up stays until handled.
        assertEquals(PromptPresentation(fullScreen = true, openNow = true), promptPresentation(interactive = true, keyguardLocked = false, fullScreenAllowed = true, backgroundStartAllowed = true))
        // Locked, or screen off: only the full-screen intent; Android opens the page behind the lock screen.
        assertEquals(PromptPresentation(fullScreen = true, openNow = false), promptPresentation(interactive = true, keyguardLocked = true, fullScreenAllowed = true, backgroundStartAllowed = true))
        assertEquals(PromptPresentation(fullScreen = true, openNow = false), promptPresentation(interactive = false, keyguardLocked = false, fullScreenAllowed = true, backgroundStartAllowed = true))
        assertEquals(PromptPresentation(fullScreen = true, openNow = false), promptPresentation(interactive = false, keyguardLocked = true, fullScreenAllowed = true, backgroundStartAllowed = true))
        // The owner revoked full-screen notifications, or the privilege is missing: never worked around.
        assertEquals(PromptPresentation(fullScreen = false, openNow = true), promptPresentation(interactive = true, keyguardLocked = false, fullScreenAllowed = false, backgroundStartAllowed = true))
        assertEquals(PromptPresentation(fullScreen = true, openNow = false), promptPresentation(interactive = true, keyguardLocked = false, fullScreenAllowed = true, backgroundStartAllowed = false))
    }

    @Test
    fun theJournalKeepsTheLastLinesWithTimesAcrossInstances() {
        val file = File(folder.root, "journal.txt")
        var clock = 1_000L
        val journal = TwoStepJournal(file, maxLines = 3) { clock++ }
        val diagnostics = TwoStepDiagnostics(Random(1)) { journal.append(it) }
        for (event in listOf(TwoStepEvent.RECEIVED, TwoStepEvent.MATCHED, TwoStepEvent.FETCHED, TwoStepEvent.SHOWN)) diagnostics.event(event, "s")

        // A new instance (a restarted process) reads the same lines.
        assertEquals(listOf("1001 event=matched session=s", "1002 event=fetched session=s", "1003 event=shown session=s"), TwoStepJournal(file).read())
    }

    @Test
    fun unchangedReadinessSnapshotsAreWrittenOnce() {
        val journal = TwoStepJournal(File(folder.root, "journal.txt")) { 1L }
        val diagnostics = TwoStepDiagnostics(Random(1)) { journal.append(it) }
        val ready = TwoStepReadiness(true, true, SelfRegistration.ALLOWED, true, 5, 5, true, true)
        repeat(50) { ready.log(diagnostics) }
        diagnostics.event(TwoStepEvent.RECEIVED, "s")
        ready.copy(chimeRegisteredAccounts = 4).log(diagnostics)
        ready.log(diagnostics)
        assertEquals(listOf("readiness", "received", "readiness", "readiness"), journal.read().map { it.substringAfter("event=").substringBefore(' ') })
    }

    @Test
    fun anUnwritableJournalNeverThrows() {
        val notADirectory = folder.newFile("plain-file")
        val journal = TwoStepJournal(File(notADirectory, "journal.txt"))
        journal.append("event=received")
        assertEquals(emptyList<String>(), journal.read())
    }
}
