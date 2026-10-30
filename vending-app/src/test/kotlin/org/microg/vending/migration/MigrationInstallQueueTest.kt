/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationInstallQueueTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun selected() = listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) })
    private fun state(status: String, installed: Boolean = false, version: Long = 0) = Bundle().apply {
        putString("package_name", "org.example.app"); putString("status", status)
        putBoolean("installed", installed); putLong("installed_version_code", version); putLong("version_code", 12)
    }
    @Before fun clear() { context.getSharedPreferences("migration-installs", 0).edit().clear().commit() }
    @Test fun unavailableDocumentsNeverEnterTheSelectableCatalog() {
        val available = Bundle().apply { putString("package_name", "org.example.available"); putInt("availability", 1) }
        val missing = MigrationInstallQueue.unavailable("org.example.missing", "not_found")
        val reply = PlaySetupService.catalog(listOf(available, missing))
        fun names(key: String): List<String?> = reply.getParcelableArray(key)!!.map { it as Bundle }
            .flatMap { it.getParcelableArray("documents")!!.toList() }.map { (it as Bundle).getString("package_name") }
        assertEquals(listOf("org.example.available"), names("document_groups"))
        assertEquals(listOf("org.example.missing"), names("unrestorable_document_groups"))
    }
    @Test fun uncertainEnqueueIsReconciledAfterRestartWithoutAnotherInstall() {
        val calls = mutableListOf<Int>()
        val first = MigrationInstallQueue(context) { operation, name ->
            calls += operation
            if (operation == 3) state("none") else MigrationInstallQueue.unavailable(name, "store_unavailable")
        }
        first.select(selected()); assertFalse(first.dispatch("org.example.app"))
        assertEquals(setOf("org.example.app"), MigrationInstallQueue(context).pending())
        val restarted = MigrationInstallQueue(context) { operation, _ -> calls += operation; state("queued") }
        assertTrue(restarted.dispatch("org.example.app")); assertTrue(restarted.pending().isEmpty())
        assertEquals(listOf(3, 2, 3), calls)
    }
    @Test fun playRateLimitKeepsTheAppPendingAndInstallsItLaterWithoutReview() {
        val calls = mutableListOf<Int>()
        var limited = true
        val queue = MigrationInstallQueue(context) { operation, name ->
            calls += operation
            when {
                operation == 3 -> state("none")
                limited -> MigrationInstallQueue.unavailable(name, "rate_limited")
                else -> state("queued")
            }
        }
        queue.select(selected())
        assertFalse(queue.dispatch("org.example.app"))
        assertTrue(queue.throttled)
        assertEquals(setOf("org.example.app"), queue.pending())
        assertFalse(queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single().getBoolean("terminal"))
        limited = false
        assertTrue(queue.dispatch("org.example.app"))
        assertTrue(queue.pending().isEmpty())
        assertEquals(listOf(3, 2, 3, 3, 2), calls)
    }
    private fun fallbackQueue(supported: Boolean, installs: MutableList<Boolean>, answer: (Boolean) -> Bundle) =
        MigrationInstallQueue(context, { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", supported) } }) { operation, name ->
            if (operation == 3) state("none") else answer(installs.last())
        }.apply { sentOptions = { operation, options -> if (operation == 2) installs += options!!.getBoolean("owner_account_approved") } }
    @Test fun anonymousRefusalFallsBackToThePhonesGoogleAccountByItself() {
        for (error in listOf("rate_limited", "not_found", "anonymous_sign_in_required")) {
            clear()
            val installs = mutableListOf<Boolean>()
            val queue = fallbackQueue(true, installs) { owner -> if (owner) state("queued") else MigrationInstallQueue.unavailable("org.example.app", error) }
            queue.select(selected())
            assertFalse(queue.dispatch("org.example.app"))
            assertEquals(setOf("org.example.app"), queue.pending())
            assertTrue(queue.dispatch("org.example.app"))
            assertEquals(error, listOf(false, true), installs)
            assertTrue(queue.pending().isEmpty())
        }
    }
    /** Run 6: Play refused 9 queued downloads for the anonymous session ("not purchased / unavailable in your country"). */
    @Test fun aDownloadPlayRefusesTheAnonymousSessionIsQueuedOnceMoreForTheGoogleAccount() {
        for (code in listOf("app_not_purchased", "rate_limited")) {
            clear()
            val installs = mutableListOf<Boolean>()
            var download = state("none")
            fun refused() = state("failed").apply { putString("error", "failed"); putString("errorCode", code) }
            val queue = MigrationInstallQueue(context, { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { operation, _ ->
                if (operation == 3) Bundle(download) else state("queued").also { download = it }
            }.apply { sentOptions = { operation, options -> if (operation == 2) installs += options!!.getBoolean("owner_account_approved") } }
            queue.select(selected())
            assertTrue(queue.dispatch("org.example.app"))
            assertTrue(queue.pending().isEmpty())
            download = refused()
            val requeued = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
            assertEquals(code, "queued", requeued.getString("status")); assertFalse(requeued.getBoolean("terminal"))
            assertEquals(setOf("org.example.app"), queue.pending())
            assertTrue(queue.dispatch("org.example.app"))
            assertEquals(code, listOf(false, true), installs)
            // Refused for the Google account too: a failure the owner sees, not another round.
            download = refused()
            val failed = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
            assertEquals("failed", failed.getString("status")); assertTrue(failed.getBoolean("terminal"))
            assertTrue(queue.pending().isEmpty())
            assertEquals(2, installs.size)
        }
    }
    /** 10-30 customer-98: the Google account's Play offered an older version than Switch listed (Blindmate, Tinder). */
    @Test fun theGoogleAccountFallbackAcceptsTheVersionPlayOffersThatAccount() {
        val minimums = mutableListOf<Long>()
        var download = state("none")
        val queue = MigrationInstallQueue(context, { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { operation, _ ->
            if (operation == 3) Bundle(download) else state("queued").also { download = it }
        }.apply { sentOptions = { operation, options -> if (operation == 2) minimums += options!!.getLong("minimum_version_code") } }
        queue.select(selected())
        assertTrue(queue.dispatch("org.example.app"))
        download = state("failed").apply { putString("errorCode", "app_not_purchased") }
        queue.summary()
        assertTrue(queue.dispatch("org.example.app"))
        assertEquals(listOf(12L, 0L), minimums)
        download = state("installed", installed = true, version = 9)
        val installed = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertTrue(installed.getBoolean("installed")); assertTrue(installed.getBoolean("terminal"))
        assertEquals("installed", installed.getString("status"))
    }
    @Test fun anAuroraThatCannotBeAskedLeavesTheRefusedDownloadOpenUntilItCan() {
        var reachable = false
        var download = state("none")
        val installs = mutableListOf<Boolean>()
        val queue = MigrationInstallQueue(context, { _, _ ->
            if (reachable) Bundle().apply { putBoolean("owner_account_fallback", true) } else throw IllegalStateException("restarting")
        }) { operation, _ -> if (operation == 3) Bundle(download) else state("queued").also { download = it } }
            .apply { sentOptions = { operation, options -> if (operation == 2) installs += options!!.getBoolean("owner_account_approved") } }
        queue.select(selected()); queue.dispatch("org.example.app")
        download = state("failed").apply { putString("errorCode", "app_not_purchased") }
        val waiting = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertFalse(waiting.getBoolean("terminal"))
        reachable = true
        val requeued = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertEquals("queued", requeued.getString("status"))
        assertTrue(queue.dispatch("org.example.app"))
        assertEquals(listOf(false, true), installs)
    }
    @Test fun onlyTheAnonymousAttemptAsksAuroraToLeaveARefusalToFakeStore() {
        val promises = mutableListOf<Boolean>()
        val queue = fallbackQueue(true, mutableListOf()) { owner -> if (owner) state("queued") else MigrationInstallQueue.unavailable("org.example.app", "not_found") }
            .apply { val sent = sentOptions; sentOptions = { operation, options -> sent?.invoke(operation, options); if (operation == 2) promises += options!!.getBoolean("owner_retry_on_refusal") } }
        queue.select(selected())
        queue.dispatch("org.example.app"); queue.dispatch("org.example.app")
        assertEquals(listOf(true, false), promises)
    }
    @Test fun aRefusedDownloadStaysAFailureWithoutAuroraSupportOrForOtherErrors() {
        for ((supported, code) in listOf(false to "app_not_purchased", true to "install_failed")) {
            clear()
            var download = state("none")
            val queue = MigrationInstallQueue(context, { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", supported) } }) { operation, _ ->
                if (operation == 3) Bundle(download) else state("queued").also { download = it }
            }
            queue.select(selected()); queue.dispatch("org.example.app")
            download = state("failed").apply { putString("errorCode", code) }
            val result = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
            assertEquals("failed", result.getString("status")); assertTrue(result.getBoolean("terminal"))
            assertTrue(queue.pending().isEmpty())
        }
    }
    @Test fun fallbackNeverAppliesToPaidAppsOrWithoutAuroraSupport() {
        val paid = mutableListOf<Boolean>()
        val paidQueue = fallbackQueue(true, paid) { MigrationInstallQueue.unavailable("org.example.app", "paid") }
        paidQueue.select(selected()); paidQueue.dispatch("org.example.app")
        assertEquals(listOf(false), paid)
        clear()
        val legacy = mutableListOf<Boolean>()
        val legacyQueue = fallbackQueue(false, legacy) { MigrationInstallQueue.unavailable("org.example.app", "rate_limited") }
        legacyQueue.select(selected())
        repeat(3) { legacyQueue.dispatch("org.example.app") }
        assertEquals(listOf(false, false, false), legacy)
        assertTrue(legacyQueue.pending().isEmpty()) // three refusals: a visible failure the owner can retry
    }
    @Test fun whenTheGoogleAccountIsAlsoRefusedTheOwnerSeesAFailureAndRetryStartsAnonymous() {
        val installs = mutableListOf<Boolean>()
        val queue = fallbackQueue(true, installs) { MigrationInstallQueue.unavailable("org.example.app", "rate_limited") }
        queue.select(selected())
        repeat(4) { queue.dispatch("org.example.app") }
        assertEquals(listOf(false, true, true, true), installs)
        val failed = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertEquals("rate_limited", failed.getString("error")); assertTrue(failed.getBoolean("terminal"))
        queue.retry(queue.currentSessionId()!!)
        queue.dispatch("org.example.app")
        assertEquals(false, installs.last())
    }
    /** CYC-968 (10-30): the last apps of a copy failed with not_signed_in while the store's session was being lost. */
    @Test fun aStoreWithoutASessionKeepsTheAppPendingAndIsAskedToPrepareOne() {
        val installs = mutableListOf<Boolean>()
        val controls = mutableListOf<Int>()
        var signedIn = false
        val queue = MigrationInstallQueue(context, { operation, _ ->
            controls += operation
            if (operation == 6) signedIn = true
            Bundle().apply { putBoolean("owner_account_fallback", true); putBoolean("ready", true) }
        }) { operation, name ->
            when {
                operation == 3 -> state("none")
                signedIn -> state("queued")
                else -> MigrationInstallQueue.unavailable(name, "not_signed_in")
            }
        }.apply { sentOptions = { operation, options -> if (operation == 2) installs += options!!.getBoolean("owner_account_approved") } }
        queue.select(selected())
        assertFalse(queue.dispatch("org.example.app")) // anonymous: no session, try the Google account
        assertFalse(queue.dispatch("org.example.app")) // Google account: no session either, ask Aurora to prepare
        assertTrue(queue.throttled)
        assertEquals(1, controls.count { it == 6 })
        assertFalse(queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single().getBoolean("terminal"))
        assertTrue(queue.dispatch("org.example.app"))
        assertEquals(listOf(false, true, true), installs)
        assertTrue(queue.pending().isEmpty())
    }
    @Test fun aStoreThatNeverGetsASessionShowsTheOwnerARetryableFailure() {
        val controls = mutableListOf<Int>()
        val queue = MigrationInstallQueue(context, { operation, _ -> controls += operation; Bundle() }) { operation, name ->
            if (operation == 3) state("none") else MigrationInstallQueue.unavailable(name, "session_expired")
        }
        queue.select(selected())
        repeat(3) { queue.dispatch("org.example.app") }
        assertEquals(3, controls.count { it == 6 })
        val failed = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertEquals("session_expired", failed.getString("error")); assertTrue(failed.getBoolean("terminal"))
        assertEquals(listOf("org.example.app"), queue.retryDocuments(queue.currentSessionId()!!).map { it.getString("package_name") })
    }
    @Test fun theGoogleAccountErrorIsNotReplacedByAnOlderFailedDownload() {
        val installs = mutableListOf<Boolean>()
        val queue = MigrationInstallQueue(context, { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { operation, name ->
            when {
                operation == 3 -> state("failed").apply { putString("error", "rate_limited") } // an earlier attempt's row
                installs.last() -> MigrationInstallQueue.unavailable(name, "no_google_account")
                else -> MigrationInstallQueue.unavailable(name, "not_found")
            }
        }.apply { sentOptions = { operation, options -> if (operation == 2) installs += options!!.getBoolean("owner_account_approved") } }
        queue.select(selected())
        assertFalse(queue.dispatch("org.example.app"))
        queue.dispatch("org.example.app")
        assertEquals(listOf(false, true), installs)
        repeat(2) {
            val receipt = queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
            assertEquals("no_google_account", receipt.getString("error")); assertTrue(receipt.getBoolean("terminal"))
        }
    }
    @Test fun queueNeverReportsDownloadingOrOldInstalledVersionAsComplete() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("downloading", false, 11) }
        queue.select(selected()); assertFalse(queue.summary().getBoolean("all_installed"))
        val restarted = MigrationInstallQueue(context) { _, _ -> state("none", true, 11) }
        assertTrue(restarted.needsEnqueue("org.example.app", state("none", true, 11)))
        assertFalse(restarted.summary().getBoolean("all_installed"))
    }
    @Test fun unavailableAppKeepsItsReasonAcrossFreshPreferencesAndStatusQueries() {
        val first = MigrationInstallQueue(context) { operation, name ->
            if (operation == 3) state("none") else MigrationInstallQueue.unavailable(name, "not_found")
        }
        first.select(selected()); assertTrue(first.dispatch("org.example.app"))
        val restarted = MigrationInstallQueue(context) { _, _ -> state("none") }
        val result = restarted.summary().getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("not_found", result.getString("error")); assertFalse(result.getBoolean("installed"))
    }
    @Test fun catalogChunksAndSetupChunksStayInOneSessionButNewTransferIsSeparate() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("failed") }
        val first = queue.catalogSession()
        assertEquals(first, queue.catalogSession())
        queue.select(selected())
        val other = Bundle().apply { putString("package_name", "org.example.second"); putLong("version_code", 2) }
        queue.select(listOf(other))
        assertEquals(setOf("org.example.app", "org.example.second"), queue.selected(first))
        val second = queue.catalogSession()
        assertNotEquals(first, second)
        assertTrue(queue.selected(second).isEmpty())
        assertEquals(2, queue.sessions().size)
    }
    @Test fun retriesOnlySessionFailuresAndNeverPreviouslyInstalledApps() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("failed") }
        val first = queue.beginTransfer()
        queue.select(selected())
        queue.save("org.example.app", state("failed"), true, first)
        assertEquals(1, queue.retryDocuments(first).size)
        val second = queue.beginTransfer()
        queue.select(selected())
        queue.save("org.example.app", state("installed", true, 12), true, second)
        queue.save("org.example.app", state("removed"), true, second)
        assertTrue(queue.retryDocuments(second).isEmpty())
        queue.retry(first)
        assertEquals(setOf("org.example.app"), queue.pending(first))
        assertTrue(queue.pending(second).isEmpty())
    }
    @Test fun completedReceiptsDoNotFollowLaterSessionsAuroraRows() {
        var status = state("failed").apply { putString("error", "not_found") }
        val queue = MigrationInstallQueue(context) { _, _ -> Bundle(status) }
        val first = queue.beginTransfer(); queue.select(selected()); queue.finishSelection(first); queue.dispatch("org.example.app", first)
        assertTrue(queue.summary(first).getBoolean("complete"))
        val second = queue.beginTransfer(); queue.select(selected())
        status = state("downloading")
        val old = queue.summary(first).getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("failed", old.getString("status"))
        assertFalse(queue.summary(second).getBoolean("complete"))
    }
    @Test fun excludedReasonsAndCatalogMetadataSurviveRestartAndChunks() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("none") }
        val id = queue.catalogSession()
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.excluded", "paid_app")))
        queue.recordCatalog(selected().map { Bundle(it).apply { putInt("availability", 1); putString("title", "Example"); putString("icon_url", "https://example.org/icon") } })
        queue.select(selected())
        val docs = MigrationInstallQueue(context) { _, _ -> state("none") }.summary(id).getParcelableArray("cyclon_app_results")!!.map { it as Bundle }
        assertEquals("paid_app", docs.single { it.getString("package_name") == "org.example.excluded" }.getString("error"))
        assertEquals("Example", docs.single { it.getString("package_name") == "org.example.app" }.getString("title"))
    }
    @Test fun removedFinishedAuroraRowNeverEnqueuesAgainAndBecomesTerminal() {
        var installs = 0
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) installs++
            state("installed").apply { putBoolean("package_present", false) }
        }
        queue.select(selected()); queue.finishSelection(queue.currentSessionId()!!); queue.dispatch("org.example.app")
        assertTrue(queue.summary().getBoolean("complete"))
        assertEquals(0, installs)
        assertTrue(queue.retryDocuments().isEmpty())
    }
    @Test fun pauseMovesToNewTransferAndResumeStopsRenewal() {
        val calls = mutableListOf<Pair<Int, String?>>()
        val queue = MigrationInstallQueue(context, storeCall = { _, _ -> state("queued") }, controlCall = { operation, options ->
            calls += operation to options.getString("session_id")
            Bundle().apply { putBoolean("success", true) }
        })
        val first = queue.catalogSession(); queue.pause(first); queue.select(selected())
        val next = queue.catalogSession(); queue.renewPause(first); queue.renewPause(next)
        assertEquals(listOf(4 to first, 5 to first, 4 to next, 4 to next), calls)
        queue.resume(next); calls.clear(); queue.renewPause(next); assertTrue(calls.isEmpty())
    }

    @Test fun legacyAmbiguousRequestsBecomeReviewableReceiptsWithoutAutomaticReplay() {
        context.getSharedPreferences("migration-installs", 0).edit()
            .putStringSet("packages", setOf("org.example.app")).putStringSet("pending", setOf("org.example.app"))
            .putLong("org.example.app:expectedVersion", 12).commit()
        var installs = 0
        val queue = MigrationInstallQueue(context) { _, _ -> installs++; state("none") }
        assertEquals("previous-transfer", queue.currentSessionId())
        assertTrue(queue.pending().isEmpty())
        val receipt = queue.summary().getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("previous_transfer_requires_review", receipt.getString("error"))
        assertEquals(0, installs)
        assertEquals(1, queue.retryDocuments().size)
    }

    @Test fun uncertainReplyAndMissingReceiptRequireOwnerApprovalBeforeAnotherEnqueue() {
        var installs = 0
        val first = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { installs++; MigrationInstallQueue.unavailable(name, "store_unavailable") } else state("none")
        }
        first.select(selected()); assertFalse(first.dispatch("org.example.app"))
        val restarted = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) installs++
            state("none")
        }
        assertTrue(restarted.dispatch("org.example.app"))
        val receipt = restarted.summary().getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("installation_requires_review", receipt.getString("error"))
        assertEquals(1, installs)
        restarted.retry(restarted.currentSessionId()!!)
        restarted.dispatch("org.example.app")
        assertEquals(2, installs)
    }
    @Test fun oldFinishedDownloadDoesNotBlockSelectedNewVersionOrBecomeOwnerRemoval() {
        val calls = mutableListOf<Int>()
        val queue = MigrationInstallQueue(context) { operation, _ ->
            calls += operation
            if (operation == 3) state("installed", true, 11).apply { putBoolean("package_present", true); putLong("version_code", 11) } else state("queued")
        }
        queue.select(selected()); assertTrue(queue.dispatch("org.example.app"))
        assertEquals(listOf(3, 2), calls)
        assertFalse(queue.summary().getBoolean("complete"))
        assertTrue(queue.retryDocuments().isEmpty())
    }
    @Test fun pauseStartsNewSessionEvenAfterPreviousTransferWasCancelledBeforeSelection() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("none") }
        val first = queue.startPausedTransfer()
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.old", "not_found")))
        val next = queue.startPausedTransfer()
        assertNotEquals(first, next)
        assertEquals(next, queue.catalogSession())
        assertTrue(queue.summary(next).getParcelableArray("cyclon_app_results")!!.isEmpty())
    }

    @Test fun summaryObservedSuccessBeforeDispatchIsAdoptedWithoutFalseOwnerRemoval() {
        var present = true
        var installs = 0
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) installs++
            state("installed", present, if (present) 12 else 0).apply { putBoolean("package_present", present) }
        }
        val id = queue.beginTransfer(); queue.select(selected()); queue.finishSelection(id)
        assertTrue(queue.summary(id).getBoolean("all_installed"))
        assertTrue(queue.dispatch("org.example.app", id))
        assertTrue(queue.summary(id).getBoolean("complete"))
        assertTrue(queue.summary(id).getBoolean("all_installed"))
        assertEquals(0, installs)
        assertTrue(queue.pending(id).isEmpty())
    }
    @Test fun noSelectionChunksAreAcceptedAfterExplicitClosure() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("queued") }
        val id = queue.beginTransfer(); queue.select(selected()); queue.finishSelection(id)
        assertThrows(IllegalStateException::class.java) { queue.select(selected()) }
    }

    @Test fun legacyStoreReturningSettledLowerOfferNeedsReviewWithoutRepeatedInstallCalls() {
        var installs = 0
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) installs++
            state("installed", true, 12).apply { putBoolean("package_present", true) }
        }
        val id = queue.beginTransfer()
        queue.select(listOf(Bundle(selected().single()).apply { putLong("version_code", 13) }))
        queue.finishSelection(id)
        queue.dispatch("org.example.app", id)
        val summary = queue.summary(id)
        assertTrue(summary.getBoolean("complete"))
        assertFalse(summary.getBoolean("all_installed"))
        val receipt = summary.getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("selected_version_unavailable", receipt.getString("error"))
        assertEquals("owner_action_required", receipt.getString("status"))
        assertTrue(queue.pending(id).isEmpty())
        repeat(3) { queue.dispatch("org.example.app", id); queue.summary(id) }
        assertEquals(1, installs)
    }

}
