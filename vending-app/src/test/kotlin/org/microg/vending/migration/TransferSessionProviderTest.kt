/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBinder

/** Model clearing a remote provider caller; Robolectric normally leaves its fake calling UID unchanged. */
@Implements(Binder::class)
class TransferSessionBinderIdentityShadow : ShadowBinder() {
    companion object {
        var clears = 0
        var restores = 0
        @JvmStatic @Implementation fun clearCallingIdentity(): Long {
            val uid = Binder.getCallingUid()
            clears++
            ShadowBinder.setCallingUid(Process.myUid())
            return uid.toLong()
        }
        @JvmStatic @Implementation fun restoreCallingIdentity(token: Long) {
            restores++
            ShadowBinder.setCallingUid(token.toInt())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [TransferSessionBinderIdentityShadow::class])
class TransferSessionProviderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: TransferSessionProvider
    private val platform = Signature("01234567")
    private val untrusted = Signature("89abcdef")
    private val companionUid = 31415
    private val companion = "ai.cyclon.migration"

    @Before fun setUp() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        context.getSharedPreferences("migration-notifications", 0).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        MigrationSetupReceivers.clearForTests()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        install("android", Process.SYSTEM_UID, ApplicationInfo.FLAG_SYSTEM, platform)
        install(companion, companionUid, ApplicationInfo.FLAG_SYSTEM, platform)
        ShadowBinder.setCallingUid(companionUid)
        TransferSessionBinderIdentityShadow.clears = 0
        TransferSessionBinderIdentityShadow.restores = 0
        provider = TransferSessionProvider().also {
            it.attachInfo(context, ProviderInfo().apply {
                authority = TransferSessionProvider.AUTHORITY
                name = TransferSessionProvider::class.java.name
                packageName = context.packageName
                exported = true
            })
        }
        assertEquals(PackageManager.SIGNATURE_MATCH, context.packageManager.checkSignatures("android", companion))
    }
    @After fun cleanUp() { ShadowBinder.reset() }

    @Suppress("DEPRECATION")
    private fun install(name: String, uid: Int, flags: Int, signature: Signature) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid; this.flags = flags }
            signatures = arrayOf(signature)
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signature)) }
        })
    }
    private fun queue(status: String = "downloading") = MigrationInstallQueue(context) { _, name -> Bundle().apply {
        putString("package_name", name)
        putString("status", status)
        putLong("version_code", 12)
        putBoolean("installed", false)
    } }
    private fun selected(): Pair<MigrationInstallQueue, String> {
        val queue = queue()
        val id = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) }))
        return queue to id
    }
    private fun ended(outcome: String = "success", id: String? = null): Bundle =
        provider.call("transferEnded", null, Bundle().apply {
            putString("outcome", outcome)
            id?.let { putString("session_id", it) }
        })

    @Test fun authorizedSessionQueryDoesNotCreateATransferAndRestoresCallingIdentity() {
        val reply = provider.call("getCurrentSession", null, null)
        assertNull(reply.getString("session_id"))
        assertNull(queue().currentSessionId())
        assertTrue(queue().sessions().isEmpty())
        assertEquals(companionUid, Binder.getCallingUid())
        assertEquals(1, TransferSessionBinderIdentityShadow.clears)
        assertEquals(1, TransferSessionBinderIdentityShadow.restores)
    }

    @Test fun wizardSuccessAndCancelCloseSelectionWithoutClaimingAnInstallSucceeded() {
        for (outcome in listOf("success", "cancelled")) {
            val (queue, id) = selected()
            assertEquals(id, provider.call("getCurrentSession", null, null).getString("session_id"))
            val reply = ended(outcome, id)
            assertTrue(reply.getBoolean("accepted"))
            assertEquals(id, reply.getString("session_id"))
            assertTrue(reply.getBoolean("selection_closed"))
            assertFalse(reply.containsKey("all_installed"))
            val state = queue.summary(id)
            assertTrue(state.getBoolean("selection_closed"))
            assertFalse(state.getBoolean("complete"))
            assertFalse(state.getBoolean("all_installed"))
            assertEquals(companionUid, Binder.getCallingUid())
        }
    }

    @Test fun omittedSessionClosesCurrentButStaleIdentityCannotCloseItsSuccessor() {
        val (queue, oldId) = selected()
        val newId = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) }))
        val stale = ended(id = oldId)
        assertFalse(stale.getBoolean("accepted"))
        assertEquals("stale_session", stale.getString("error"))
        assertFalse(queue.summary(newId).getBoolean("selection_closed"))
        assertTrue(ended(outcome = "cancelled").getBoolean("accepted"))
        assertTrue(queue.summary(newId).getBoolean("selection_closed"))
        assertFalse(queue.summary(oldId).getBoolean("selection_closed"))
    }

    @Test fun noCurrentSessionReturnsAnExplicitNoOpAndDoesNotCreateOne() {
        val reply = ended()
        assertFalse(reply.getBoolean("accepted"))
        assertEquals("no_active_session", reply.getString("error"))
        assertNull(queue().currentSessionId())
        assertTrue(queue().sessions().isEmpty())
    }

    @Test fun companionClosureCompletesTheMainFlowWithoutSwitchFinalHoldAndReplacesItsReviewPrompt() {
        var installed = false
        var installRequests = 0
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) installRequests++
            Bundle().apply {
                putString("package_name", name)
                putString("status", if (installed) "installed" else if (operation == 2) "queued" else "none")
                putLong("version_code", 12)
                putBoolean("installed", installed)
                putBoolean("package_present", installed)
                putLong("installed_version_code", if (installed) 12 else 0)
            }
        }
        val id = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) }))
        queue.dispatch("org.example.app", id)
        installed = true
        val open = queue.summary(id)
        assertTrue(open.getBoolean("installs_settled"))
        assertFalse(open.getBoolean("selection_closed"))
        assertFalse(open.getBoolean("complete"))
        val received = mutableListOf<Pair<Int, Bundle>>()
        MigrationSetupReceivers.register(object : ResultReceiver(null) {
            override fun onReceiveResult(code: Int, state: Bundle?) { received += code to Bundle(state) }
        }, id)
        MigrationSetupReceivers.publish(queue)
        assertEquals(listOf(2), received.map { it.first })
        MigrationNotifications.selectionOpen(context, id, open)
        val manager = context.getSystemService(NotificationManager::class.java)
        val before = manager.activeNotifications.single()
        assertEquals("Finish app selection", before.notification.extras.getCharSequence("android.title").toString())

        // The companion's wizard-return callback supplies the missing closure; no Switch tx9 is sent.
        val acknowledgement = ended("success", id)
        assertTrue(acknowledgement.getBoolean("accepted"))
        assertFalse(acknowledgement.containsKey("all_installed"))
        val complete = queue.summary(id)
        assertTrue(complete.getBoolean("selection_closed"))
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        MigrationSetupReceivers.publish(queue)
        assertEquals(listOf(2, 1), received.map { it.first })
        assertTrue(received.last().second.getBoolean("complete"))
        MigrationNotifications.completed(context, id, complete)
        val after = manager.activeNotifications.single()
        assertEquals(before.tag, after.tag)
        assertEquals(before.id, after.id)
        assertEquals("Transferred apps are ready to review", after.notification.extras.getCharSequence("android.title").toString())
        val openResults = shadowOf(after.notification.contentIntent).savedIntent
        assertEquals("ai.cyclon.migration.APP_RESULTS", openResults.action)
        assertEquals(id, openResults.getStringExtra("session_id"))
        assertTrue(after.notification.contentIntent.isImmutable)
        assertEquals(1, installRequests)
    }

    @Test fun duplicateWizardReturnIsIdempotentAndCannotReplaceTheFirstOutcome() {
        val (queue, id) = selected()
        val first = ended("success", id)
        assertTrue(first.getBoolean("accepted"))
        assertFalse(first.getBoolean("already_closed"))
        val repeated = ended("cancelled", id)
        assertTrue(repeated.getBoolean("accepted"))
        assertTrue(repeated.getBoolean("already_closed"))
        assertEquals("success", repeated.getString("transfer_outcome"))
        assertEquals("success", queue.summary(id).getString("transfer_outcome"))
        assertEquals(setOf("org.example.app"), queue.pending(id))
        assertFalse(queue.summary(id).getBoolean("all_installed"))
    }

    @Test fun wrongUidCannotQueryOrCloseEvenWhenItClaimsTheCompanionName() {
        val (queue, id) = selected()
        ShadowBinder.setCallingUid(companionUid + 1)
        assertThrows(SecurityException::class.java) { provider.call("getCurrentSession", null, null) }
        assertThrows(SecurityException::class.java) {
            provider.call("transferEnded", null, Bundle().apply {
                putString("outcome", "success"); putString("package_name", companion); putString("session_id", id)
            })
        }
        assertFalse(queue.summary(id).getBoolean("selection_closed"))
        assertEquals(0, TransferSessionBinderIdentityShadow.clears)
        assertEquals(0, TransferSessionBinderIdentityShadow.restores)
    }

    @Test fun systemPackageWithWrongSigningCertificateIsRejected() {
        val (queue, id) = selected()
        install(companion, companionUid, ApplicationInfo.FLAG_SYSTEM, untrusted)
        assertNotEquals(PackageManager.SIGNATURE_MATCH, context.packageManager.checkSignatures("android", companion))
        assertThrows(SecurityException::class.java) { provider.call("getCurrentSession", null, null) }
        assertThrows(SecurityException::class.java) { ended(id = id) }
        assertFalse(queue.summary(id).getBoolean("selection_closed"))
        assertEquals(0, TransferSessionBinderIdentityShadow.clears)
    }

    @Test fun platformSignedButNonSystemPackageIsRejected() {
        val (queue, id) = selected()
        install(companion, companionUid, 0, platform)
        assertThrows(SecurityException::class.java) { ended(id = id) }
        assertFalse(queue.summary(id).getBoolean("selection_closed"))
        assertEquals(0, TransferSessionBinderIdentityShadow.clears)
    }

    @Test fun updatedSystemPlatformPackageAndExactSystemUidCompanionAreAccepted() {
        val (_, firstId) = selected()
        install(companion, companionUid, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, platform)
        assertTrue(ended(id = firstId).getBoolean("accepted"))
        val (_, secondId) = selected()
        install(companion, Process.SYSTEM_UID, ApplicationInfo.FLAG_SYSTEM, untrusted)
        ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        assertTrue(ended(outcome = "cancelled", id = secondId).getBoolean("accepted"))
        assertEquals(Process.SYSTEM_UID, Binder.getCallingUid())
    }

    @Test fun unrelatedSystemUidCannotBypassTheExactCompanionUidCheck() {
        val (queue, id) = selected()
        ShadowBinder.setCallingUid(Process.SYSTEM_UID)
        assertThrows(SecurityException::class.java) { ended(id = id) }
        assertFalse(queue.summary(id).getBoolean("selection_closed"))
        assertEquals(0, TransferSessionBinderIdentityShadow.clears)
    }

    @Test fun invalidContractFieldsCannotCloseSelectionOrChangeCallerIdentity() {
        val (queue, id) = selected()
        assertThrows(IllegalArgumentException::class.java) { provider.call("transferEnded", null, null) }
        assertThrows(IllegalArgumentException::class.java) { ended("unknown", id) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("transferEnded", null, Bundle().apply {
            putString("outcome", "success"); putInt("session_id", 7)
        }) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("getCurrentSession", "unexpected", null) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("getCurrentSession", null, Bundle().apply { putBoolean("extra", true) }) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("unknown", null, null) }
        assertFalse(queue.summary(id).getBoolean("selection_closed"))
        assertEquals(companionUid, Binder.getCallingUid())
        assertEquals(0, TransferSessionBinderIdentityShadow.clears)
    }
}
