/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import java.util.Collections

/** Uses Switch chc.java transaction numbers and presence-tagged Parcelable layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [WireResultReceiverShadow::class])
class PlaySetupProtocolTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val calls = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
    private val controls = Collections.synchronizedList(mutableListOf<Pair<Int, Bundle>>())
    private val receipts = mutableMapOf<String, Bundle>()
    private val catalog = mutableMapOf<String, Bundle>()
    /** Answers to give, in order, before falling back to [catalog]. */
    private val lookups = mutableMapOf<String, ArrayDeque<Bundle>>()
    private var pauseSucceeds = true
    private var ready = true
    private var supportsHold = true
    private lateinit var queue: MigrationInstallQueue
    private lateinit var protocol: PlaySetupProtocol

    @Before fun setUp() {
        MigrationSetupReceivers.clearForTests()
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        queue = MigrationInstallQueue(context, storeCall = { operation, name ->
            calls += operation to name
            when (operation) {
                1 -> Bundle(lookups[name]?.removeFirstOrNull() ?: catalog[name] ?: document(name))
                2 -> receipt(name, "queued")
                else -> Bundle(receipts[name] ?: receipt(name, "none"))
            }
        }, controlCall = { operation, options ->
            controls += operation to Bundle(options)
            Bundle().apply {
                putBoolean("automatic_update_hold", supportsHold)
                putBoolean("owner_approved_retry", true)
                putBoolean("success", pauseSucceeds)
                putBoolean("ready", ready)
                if (!ready || !pauseSucceeds) putString("error", "store_unavailable")
            }
        })
        protocol = PlaySetupProtocol(queue, { true }, { it() })
    }

    @After fun tearDown() {
        protocol.close()
        MigrationSetupReceivers.clearForTests()
    }

    private fun document(name: String = APP) = Bundle().apply {
        putString("package_name", name)
        putString("title", "Example app")
        putString("icon_url", "https://example.org/icon.png")
        putString("developer_name", "Example developer")
        putInt("availability", 1)
        putLong("version_code", 12)
    }

    private fun receipt(name: String = APP, status: String, installed: Boolean = false) = Bundle().apply {
        putString("package_name", name)
        putString("status", status)
        putLong("version_code", 12)
        putBoolean("installed", installed)
        if (installed) putLong("installed_version_code", 12)
    }

    private class Receiver : ResultReceiver(null) {
        val results = mutableListOf<Pair<Int, Bundle>>()
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            results += resultCode to Bundle(resultData ?: Bundle())
        }
    }

    private fun Parcel.parcelable(value: Parcelable?) {
        writeInt(if (value == null) 0 else 1)
        value?.writeToParcel(this, 0)
    }

    private fun transact(code: Int, binder: IBinder = protocol, fields: Parcel.() -> Unit = {}): Parcel {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(PlaySetupService.DESCRIPTOR)
            request.fields()
            assertTrue("transaction $code was not handled", binder.transact(code, request, response, 0))
            response.readException()
            return response
        } catch (failure: Throwable) {
            response.recycle()
            throw failure
        } finally { request.recycle() }
    }

    private fun bundle(code: Int, fields: Parcel.() -> Unit = {}): Bundle {
        val reply = transact(code, fields = fields)
        return try {
            assertEquals(1, reply.readInt())
            Bundle.CREATOR.createFromParcel(reply).also { assertEquals(0, reply.dataAvail()) }
        } finally { reply.recycle() }
    }

    private fun void(code: Int, fields: Parcel.() -> Unit = {}) {
        transact(code, fields = fields).let { reply ->
            try { assertEquals(0, reply.dataAvail()) } finally { reply.recycle() }
        }
    }

    private fun select(name: String = APP, options: Bundle = Bundle()): Bundle = bundle(12) {
        parcelable(Bundle(options).apply { putParcelableArray("documents", arrayOf(document(name))) })
    }

    private fun resultDocuments(result: Bundle, key: String): List<Bundle> =
        result.getParcelableArray(key).orEmpty().flatMap { (it as Bundle).getParcelableArray("documents").orEmpty().toList() }
            .map { it as Bundle }

    private fun assertRejected(action: () -> Unit) {
        try { action(); fail("Malformed or unauthorized call was accepted") }
        catch (failure: Exception) { assertNotNull(failure.message) }
    }

    @Test fun descriptorCanBeQueriedWithoutTrustOrInterfaceToken() {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val untrusted = PlaySetupProtocol(queue, { false }, { it() })
            assertTrue(untrusted.transact(IBinder.INTERFACE_TRANSACTION, request, reply, 0))
            assertEquals(PlaySetupService.DESCRIPTOR, reply.readString())
            assertEquals(0, reply.dataAvail())
        } finally { request.recycle(); reply.recycle() }
    }

    @Test fun earlyUpdateTransactionsHaveNoWorkToRun() {
        val receiver = Receiver()
        void(1) { parcelable(receiver) }
        assertEquals(listOf(1), receiver.results.map { it.first })
        void(2)
        val reply = transact(3)
        try { assertEquals(0, reply.readInt()); assertEquals(0, reply.dataAvail()) }
        finally { reply.recycle() }
        assertTrue(calls.isEmpty())
    }

    @Test fun optionalGoogleCatalogTransactionsReturnEmptyGroups() {
        for (code in listOf(4, 5, 6, 21)) {
            val result = bundle(code) {
                if (code != 21) writeString("owner-account-is-never-forwarded")
                if (code == 6) writeLong(123456789L)
            }
            assertNotNull(result.getParcelableArray("document_groups"))
            assertTrue(resultDocuments(result, "document_groups").isEmpty())
        }
        assertTrue(calls.isEmpty())
        assertTrue(controls.isEmpty())
    }

    @Test fun compatibleAndFilteredCatalogTransactionsPrepareAnonymousLookupAndRetainMetadata() {
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "not_found")
        for (code in listOf(7, 22)) {
            calls.clear(); controls.clear()
            val result = bundle(code) {
                writeString("private-owner-account")
                writeStringArray(arrayOf(APP, MISSING, APP))
            }
            assertEquals(listOf(6), controls.map { it.first })
            assertEquals(setOf(1 to APP, 1 to MISSING), calls.toSet())
            assertEquals(2, calls.size)
            val app = resultDocuments(result, "document_groups").single()
            assertEquals(APP, app.getString("package_name"))
            assertEquals("https://example.org/icon.png", app.getString("icon_url"))
            assertEquals("Example developer", app.getString("developer_name"))
            assertEquals(MISSING, resultDocuments(result, "unrestorable_document_groups").single().getString("package_name"))
        }
    }

    @Test fun anonymousPreparationFailurePersistsRecoverableExcludedApps() {
        ready = false
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "anonymous_sign_in_required")
        val result = bundle(7) { writeString(null); writeStringArray(arrayOf(MISSING)) }
        val missing = resultDocuments(result, "unrestorable_document_groups").single()
        assertTrue(missing.getBoolean("recoverable"))
        assertEquals("store_unavailable", missing.getString("error"))
        assertNotNull(result.getBundle("error"))
        val state = bundle(19) { parcelable(Bundle()) }
        val persisted = state.getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals(MISSING, persisted.getString("package_name"))
        assertEquals("store_unavailable", persisted.getString("error"))
        assertTrue(persisted.getBoolean("excluded"))
    }

    /** Run 6: one app's passing failure made Switch drop the whole batch of 50 (lqm.k after three retries). */
    @Test fun oneAppTheStoreCannotCheckYetDoesNotSinkItsBatch() {
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "google_approval_needed").apply { putBoolean("recoverable", true) }
        val result = bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        assertNull(result.getBundle("error"))
        assertEquals(APP, resultDocuments(result, "document_groups").single().getString("package_name"))
        val missing = resultDocuments(result, "unrestorable_document_groups").single()
        assertEquals(MISSING, missing.getString("package_name"))
        assertTrue(missing.getBoolean("recoverable"))
        assertEquals(2, calls.count { it == 1 to MISSING }) // asked once more before leaving it out
        assertEquals(1, calls.count { it == 1 to APP })
    }

    @Test fun theOwnerCanStillAddAnAppSwitchLeftOutForAPassingFailure() {
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "rate_limited").apply { putBoolean("recoverable", true) }
        bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        select()
        val id = queue.currentSessionId()!!
        assertEquals(listOf(MISSING), queue.retryDocuments(id).map { it.getString("package_name") })
        queue.retry(id, MISSING)
        assertTrue(MISSING in queue.selected(id)); assertTrue(MISSING in queue.pending(id))
    }

    @Test fun anAppThatIsNotOnPlayCannotBeAddedLater() {
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "not_found")
        bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        select()
        assertTrue(queue.retryDocuments(queue.currentSessionId()!!).isEmpty())
    }

    @Test fun anAppPlayRefusedForAMomentIsListedAfterItsSecondLookup() {
        lookups[MISSING] = ArrayDeque(listOf(MigrationInstallQueue.unavailable(MISSING, "rate_limited").apply { putBoolean("recoverable", true) }))
        val result = bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        assertNull(result.getBundle("error"))
        assertEquals(setOf(APP, MISSING), resultDocuments(result, "document_groups").map { it.getString("package_name") }.toSet())
    }

    @Test fun anAppThatIsNotOnPlayIsNotAskedAgain() {
        catalog[MISSING] = MigrationInstallQueue.unavailable(MISSING, "not_found")
        val result = bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        assertNull(result.getBundle("error"))
        assertEquals(1, calls.count { it == 1 to MISSING })
    }

    @Test fun aStoreOutageStillFailsTheBatchSoSwitchRetries() {
        for (name in listOf(APP, MISSING)) catalog[name] = MigrationInstallQueue.unavailable(name, "store_unavailable").apply { putBoolean("recoverable", true) }
        val result = bundle(7) { writeString(null); writeStringArray(arrayOf(APP, MISSING)) }
        assertEquals("StoreUnavailableException", result.getBundle("error")!!.getString("exception_type"))
    }

    @Test fun legacySetupTransactionAcceptsTypedBundleArrayWithoutBundleReply() {
        void(8) { writeTypedArray(arrayOf(document()), 0) }
        assertEquals(setOf(APP), queue.selected())
        assertEquals(setOf(APP), queue.pending())
    }

    @Test fun setupDocumentsAcceptsMatchedAppTypeAndPreservesTransferOptionsAcrossBatches() {
        // hmg.java:550 supplies app-details type 3; ejj.java:105 copies it to documents_type.
        val options = Bundle().apply {
            putInt("documents_type", 3)
            putInt("restore_source", 7)
            putBoolean("disable_pre_archiving", true)
        }
        assertTrue(select(options = options).getBoolean("accepted"))
        val firstSession = queue.currentSessionId()
        assertTrue(select(MISSING, options).getBoolean("accepted"))
        assertEquals(firstSession, queue.currentSessionId())
        assertEquals(setOf(APP, MISSING), queue.selected())
        val state = bundle(19) { parcelable(Bundle()) }
        val retained = state.getBundle("setup_options")!!
        assertEquals(3, retained.getInt("documents_type"))
        assertEquals(7, retained.getInt("restore_source"))
        assertTrue(retained.getBoolean("disable_pre_archiving"))
    }

    @Test fun unsupportedDocumentTypeIsNotEnqueued() {
        val reply = select(options = Bundle().apply { putInt("documents_type", 99) })
        assertFalse(reply.getBoolean("accepted"))
        assertTrue(queue.selected().isEmpty())
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun pauseTransactionReportsZeroOnlyAfterAuroraConfirmsHold() {
        val receiver = Receiver()
        bundle(13) { parcelable(receiver); parcelable(Bundle().apply { putString("caller_id", "com.google.android.apps.restore") }) }
        assertEquals(listOf(0), receiver.results.map { it.first })
        assertEquals(listOf(7, 4), controls.map { it.first })
        assertEquals(queue.currentSessionId(), controls.last().second.getString("session_id"))
        assertTrue(receiver.results.single().second.getBoolean("success"))
    }

    @Test fun failedPauseDoesNotSendSuccessCallback() {
        pauseSucceeds = false
        val receiver = Receiver()
        val reply = bundle(13) { parcelable(receiver); parcelable(Bundle()) }
        assertTrue(receiver.results.isEmpty())
        assertEquals("store_unavailable", reply.getBundle("error")!!.getString("error"))
    }

    @Test fun unsupportedPauseReturnsTypedErrorAndGatesIgnoredErrorsBeforeSetup() {
        supportsHold = false
        val receiver = Receiver()
        val paused = bundle(13) { parcelable(receiver); parcelable(Bundle()) }
        assertEquals("store_update_required", paused.getBundle("error")!!.getString("error"))
        assertTrue(receiver.results.isEmpty())
        assertNull(queue.currentSessionId())
        assertEquals(listOf(7), controls.map { it.first })
        val catalog = bundle(7) { writeString(null); writeStringArray(arrayOf(APP)) }
        assertNotNull(catalog.getBundle("error"))
        assertTrue(calls.isEmpty())
        val setup = select()
        assertFalse(setup.getBoolean("accepted"))
        assertNotNull(setup.getBundle("error"))
        assertTrue(queue.selected().isEmpty())
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun resumeDoesNotCloseASelectionThatCanStillReceiveChunks() {
        select()
        bundle(14) { parcelable(Bundle()) }
        assertFalse(queue.summary().getBoolean("selection_closed"))
        select(MISSING)
        assertEquals(setOf(APP, MISSING), queue.selected())
    }

    @Test fun resumeTransactionReleasesTheSameSession() {
        bundle(13) { parcelable(null); parcelable(Bundle()) }
        val id = queue.currentSessionId()
        val result = bundle(14) { parcelable(Bundle().apply { putString("caller_id", "com.google.android.apps.restore") }) }
        assertTrue(result.getBoolean("success"))
        assertEquals(listOf(7, 4, 5), controls.map { it.first })
        assertEquals(id, controls.last().second.getString("session_id"))
    }

    @Test fun registerAndUnregisterMatchReceiverBinderAcrossSeparateParcelObjects() {
        select()
        receipts[APP] = receipt(status = "downloading")
        val receiver = Receiver()
        bundle(15) { parcelable(receiver); parcelable(Bundle()) }
        assertEquals(listOf(2), receiver.results.map { it.first })
        assertEquals(queue.currentSessionId(), receiver.results.single().second.getString("session_id"))
        protocol.publish()
        assertEquals(listOf(2, 2), receiver.results.map { it.first })
        bundle(16) { parcelable(receiver); parcelable(Bundle()) }
        protocol.publish()
        assertEquals(2, receiver.results.size)
    }

    @Test fun receiverSurvivesTheOriginalBoundProtocolBeingDestroyed() {
        select()
        queue.finishSelection(queue.currentSessionId()!!)
        queue.dispatch(APP)
        receipts[APP] = receipt(status = "downloading")
        val progress = Receiver()
        val completion = Receiver()
        bundle(15) { parcelable(progress); parcelable(Bundle()) }
        void(9) { parcelable(completion) }
        protocol.close()
        val rebound = PlaySetupProtocol(queue, { true }, { it() })
        try {
            receipts[APP] = receipt(status = "installed", installed = true)
            rebound.publish()
            assertEquals(1, progress.results.last().first)
            assertEquals(listOf(1), completion.results.map { it.first })
        } finally { rebound.close() }
    }

    @Test fun setupStateTransactionIncludesCurrentSessionAndPerAppReceipt() {
        select()
        receipts[APP] = receipt(status = "downloading")
        val state = bundle(19) { parcelable(Bundle()) }
        assertEquals(queue.currentSessionId(), state.getString("session_id"))
        assertFalse(state.getBoolean("complete"))
        val app = state.getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals(APP, app.getString("package_name"))
        assertEquals("downloading", app.getString("status"))
        assertFalse(app.getBoolean("terminal"))
    }

    @Test fun finalHoldCompletesWhenOwnerSelectedNoApps() {
        val receiver = Receiver()
        void(9) { parcelable(receiver) }
        assertEquals(listOf(1), receiver.results.map { it.first })
        assertTrue(receiver.results.single().second.getBoolean("complete"))
        assertTrue(queue.selected().isEmpty())
    }

    @Test fun finalHoldWaitsForTerminalInstallAndCompletesExactlyOnce() {
        select()
        queue.dispatch(APP)
        receipts[APP] = receipt(status = "downloading")
        val receiver = Receiver()
        void(9) { parcelable(receiver) }
        protocol.publish()
        assertTrue(receiver.results.isEmpty())
        receipts[APP] = receipt(status = "installed", installed = true)
        protocol.publish()
        protocol.publish()
        assertEquals(listOf(1), receiver.results.map { it.first })
        assertTrue(receiver.results.single().second.getBoolean("complete"))
        assertTrue(receiver.results.single().second.getBoolean("all_installed"))
    }

    @Test fun finalHoldCompletesFailedReceiptsWithoutClaimingSuccessfulInstall() {
        select()
        queue.dispatch(APP)
        receipts[APP] = receipt(status = "failed").apply { putString("error", "download_failed") }
        val receiver = Receiver()
        void(9) { parcelable(receiver) }
        assertEquals(listOf(1), receiver.results.map { it.first })
        assertTrue(receiver.results.single().second.getBoolean("complete"))
        assertFalse(receiver.results.single().second.getBoolean("all_installed"))
    }

    @Test fun finalHoldDoesNotFinishWhenAuroraStatusIsUnavailable() {
        select()
        queue.dispatch(APP)
        receipts[APP] = MigrationInstallQueue.unavailable(APP, "store_unavailable")
        val receiver = Receiver()
        void(9) { parcelable(receiver) }
        protocol.publish()
        assertTrue(receiver.results.isEmpty())
    }

    @Test fun optionalPreferencesAndPreinstallTransactionsRetainOptionsWithoutInstalling() {
        for (code in listOf(17, 18)) {
            bundle(code) { parcelable(Bundle().apply { putInt("restore_source", code) }) }
            assertEquals(code, queue.options(queue.currentSessionId()!!).getInt("restore_source"))
        }
        assertTrue(queue.selected().isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun nullReceiverPresenceTagsAreAcceptedWithoutCallbacks() {
        void(1) { parcelable(null) }
        void(9) { parcelable(null) }
        for (code in listOf(15, 16)) bundle(code) { parcelable(null); parcelable(null) }
    }

    @Test fun unknownTransactionIsUnhandled() {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(PlaySetupService.DESCRIPTOR)
            assertFalse(protocol.transact(999, request, response, 0))
            assertTrue(calls.isEmpty())
        } finally { request.recycle(); response.recycle() }
    }

    @Test fun untrustedCallerIsRejectedBeforeCatalogWork() {
        val untrusted = PlaySetupProtocol(queue, { false }, { it() })
        assertRejected { transact(7, untrusted) { writeString(null); writeStringArray(arrayOf(APP)) }.recycle() }
        assertTrue(calls.isEmpty())
        assertTrue(controls.isEmpty())
    }

    @Test fun wrongInterfaceTokenIsRejected() {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken("org.example.other")
            assertRejected { protocol.transact(3, request, response, 0); response.readException() }
        } finally { request.recycle(); response.recycle() }
    }

    @Test fun trailingFieldsAreRejectedForEveryImplementedTransaction() {
        for (code in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22)) {
            assertRejected {
                transact(code) {
                    when (code) {
                        1, 9 -> parcelable(null)
                        4, 5 -> writeString(null)
                        6 -> { writeString(null); writeLong(0) }
                        7, 22 -> { writeString(null); writeStringArray(emptyArray()) }
                        8 -> writeTypedArray(emptyArray<Bundle>(), 0)
                        13, 15, 16 -> { parcelable(null); parcelable(null) }
                        12, 14, 17, 18, 19 -> parcelable(null)
                    }
                    writeInt(123)
                }.recycle()
            }
        }
        assertTrue(calls.isEmpty())
        assertTrue(controls.isEmpty())
        assertNull(queue.currentSessionId())
    }

    @Test fun malformedPackageNamesAreRejectedBeforeLookupOrSelection() {
        assertRejected { bundle(7) { writeString(null); writeStringArray(arrayOf("../outside")) } }
        assertRejected { void(8) { writeTypedArray(arrayOf(document("bad/package")), 0) } }
        assertTrue(calls.isEmpty())
        assertTrue(queue.selected().isEmpty())
    }

    companion object {
        private const val APP = "org.example.app"
        private const val MISSING = "org.example.missing"
    }
}

/**
 * Robolectric's default shadow sends directly to the parcel-created proxy's empty callback.
 * Keep the framework implementation, which forwards through the receiver binder to the original owner.
 */
@Implements(value = ResultReceiver::class, callThroughByDefault = true)
class WireResultReceiverShadow
