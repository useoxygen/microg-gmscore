/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.Application
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.phenotype.FolsomPhenotypeClient
import org.microg.gms.phenotype.FolsomPhenotypeConfiguration
import org.microg.gms.phenotype.FolsomPromptFlagPolicy

/** Explicit live acquisition. Default instrumentation runs skip it; the host never supplies an account or token. */
@RunWith(AndroidJUnit4::class)
class FolsomServerAssignmentAndroidTest {
    @Test(timeout = 120_000) fun fetchActualDeviceAssignmentsForNativeApprovedAccountWithoutPrintingTokens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc687-folsom-server-flags")
        val context = instrumentation.targetContext
        var client: FolsomPhenotypeClient? = null
        try {
            check(com.google.android.gms.BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == "com.google.android.gms:ui")
            val run = arguments.getString("passwordAcceptanceRun") ?: throw SecurityException()
            PasswordAcceptanceAccounts.checked(run, "primary")
            val selected = PasswordAcceptanceAccounts.resolve(context, run, "primary")
            fun authorize() {
                LegacyPasskeyTicketStore.system(context)
                val current = PasswordAcceptanceAccounts.resolve(context, run, "primary")
                check(current.accountId == selected.accountId && current.account == selected.account)
                PasswordAcceptanceAccounts.verify(context, selected)
                val lock = context.getSystemService(KeyguardManager::class.java)
                check(!lock.isDeviceLocked && !lock.isKeyguardLocked)
            }
            authorize()
            val uri = Uri.parse("content://com.google.android.gms.phenotype/${FolsomPromptFlagPolicy.NAMESPACE}")
            val overrides = context.contentResolver.call(uri, FolsomPhenotypeConfiguration.CAPTURE_OVERRIDES,
                FolsomPromptFlagPolicy.NAMESPACE, null)!!.getBundle("overrides")!!
            // Local test overrides cannot masquerade as a Google rollout receipt. Do not erase another owner's settings.
            check(overrides.isEmpty)
            val active = FolsomPhenotypeClient(context, selected.account, selected.accountId, ::authorize)
            client = active
            val receipt = active.refresh()
            authorize()
            check(receipt.configurationReturned && receipt.committed)
            val assigned = FolsomPhenotypeClient.committedValues(context)
            val expected = FolsomPromptFlagPolicy.resolve(FolsomPromptFlagPolicy.NAMESPACE, assigned)
            val native = FolsomPhenotypeConfiguration.read(context)
            check(native == expected)
            val after = context.contentResolver.call(uri, FolsomPhenotypeConfiguration.CAPTURE_OVERRIDES,
                FolsomPromptFlagPolicy.NAMESPACE, null)!!.getBundle("overrides")!!
            check(after.isEmpty)
            authorize()
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("actualServerConfigurationReturned", receipt.configurationReturned)
                putBoolean("actualDeviceConfigurationCommitted", receipt.committed)
                putBoolean("nativeConsumerMatchesActualCommittedDeviceFlags", true)
                putBoolean("localOverridesAbsentAndUnchanged", true)
                putInt("supportedServerAssignedFlagCount", receipt.assignedFlagCount)
            })
        } catch (_: Exception) {
            instrumentation.sendStatus(2, Bundle().apply { putBoolean("actualDeviceConfigurationCommitted", false) })
            throw AssertionError("Native Folsom assignment acquisition did not complete; no token or account data is emitted")
        } finally { client?.cancel() }
    }
}
