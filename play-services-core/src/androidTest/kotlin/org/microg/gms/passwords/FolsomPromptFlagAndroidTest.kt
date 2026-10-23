/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.common.api.Status
import com.google.android.gms.phenotype.*
import com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.phenotype.FolsomPhenotypeConfiguration
import org.microg.gms.phenotype.FolsomPromptFlagPolicy
import org.microg.gms.phenotype.PhenotypeServiceImpl

@RunWith(AndroidJUnit4::class)
class FolsomPromptFlagAndroidTest {
    private class Callback : IPhenotypeCallbacks.Stub() {
        var flag: Flag? = null
        var configuration: Configurations? = null
        override fun onFlag(status: Status, result: Flag?) { assertTrue(status.isSuccess); flag = result }
        override fun onConfiguration(status: Status, result: Configurations?) { assertTrue(status.isSuccess); configuration = result }
        override fun onCommittedConfiguration(status: Status, result: Configurations?) { assertTrue(status.isSuccess); configuration = result }
        override fun onRegistered(status: Status?) = Unit
        override fun onWeakRegistered(status: Status?) = Unit
        override fun onUnregistered(status: Status?) = Unit
        override fun onCommitedToConfiguration(status: Status?) = Unit
        override fun onExperimentTokens(status: Status?, tokens: ExperimentTokens?) = Unit
        override fun onDogfoodsToken(status: Status?, token: DogfoodsToken?) = Unit
        override fun onDogfoodsTokenSet(status: Status?) = Unit
        override fun onSyncFinished(status: Status?, version: Long) = Unit
        override fun onFlagOverridesSet(status: Status?) = Unit
        override fun onFlagOverrides(status: Status?, overrides: FlagOverrides?) = Unit
        override fun onAppSpecificPropertiesSet(status: Status?) = Unit
        override fun onServingVersion(status: Status?, version: Long) = Unit
    }

    /** androidTest manifest targets com.google.android.gms:ui; the provider stays in the default process. */
    @Test fun actualCrossProcessOverridesDriveFreshNativeAndSdkReadsAndRestoreExactProviderState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "com.google.android.gms.auth_folsom"
        val uri = Uri.parse("content://com.google.android.gms.phenotype/$namespace")
        val resolver = context.contentResolver
        val captured = resolver.call(uri, FolsomPhenotypeConfiguration.CAPTURE_OVERRIDES, namespace, null)!!
        assertNotEquals("This regression requires the manifest's :ui instrumentation target", Process.myPid(), captured.getInt("provider_pid"))
        val original = captured.getBundle("overrides")!!
        var expected = Bundle(original)
        try {
            resolver.delete(uri, null, null)
            expected = Bundle()
            assertFalse(FolsomPhenotypeConfiguration.read(context).promptEnabled)
            fun writeBoolean(name: String, value: Boolean) {
                val count = resolver.update(uri, ContentValues().apply { put("name", name); put("value", value) }, null, null)
                if (count == 1) expected.putBoolean(name, value)
                assertEquals(1, count)
            }
            writeBoolean("ZuulMvp__enable_notifications", true)
            assertTrue(FolsomPhenotypeConfiguration.read(context).promptEnabled)
            val rows = linkedMapOf<String, String>()
            resolver.query(uri, null, null, null, null)!!.use { while (it.moveToNext()) rows[it.getString(0)] = it.getString(1) }
            assertEquals("true", rows["ZuulMvp__enable_notifications"])
            assertEquals("86400000", rows["ZuulMvp__lskf_notification_delay"])
            val callback = Callback(); val service = PhenotypeServiceImpl(context.packageName, context)
            service.getFlag(callback, namespace, "ZuulMvp__enable_notifications", 0)
            assertEquals(Flag.DATA_TYPE_BOOL, callback.flag!!.dataType); assertTrue(callback.flag!!.bool)
            service.getConfigurationSnapshot(callback, namespace, null)
            val flags = callback.configuration!!.field4.single().flags.associateBy { it.name }
            assertEquals(setOf("KeychainConsent__skip_consent", "ZuulMvp__enable_notifications", "ZuulMvp__lskf_notification_delay"), flags.keys)
            assertEquals(Flag.DATA_TYPE_LONG, flags["ZuulMvp__lskf_notification_delay"]!!.dataType)
            assertEquals(86_400_000L, flags["ZuulMvp__lskf_notification_delay"]!!.long)
            writeBoolean("KeychainConsent__skip_consent", true)
            assertFalse(FolsomPhenotypeConfiguration.read(context).promptEnabled)
            resolver.delete(uri, null, arrayOf("KeychainConsent__skip_consent"))
            expected.remove("KeychainConsent__skip_consent")
            assertTrue(FolsomPhenotypeConfiguration.read(context).promptEnabled)
            val bad = ContentValues().apply { put("name", "ZuulMvp__enable_notifications"); put("value", "true") }
            assertThrows(IllegalArgumentException::class.java) { resolver.update(uri, bad, null, null) }
            assertTrue(FolsomPhenotypeConfiguration.read(context).promptEnabled)
            resolver.query(Uri.parse("content://com.google.android.gms.phenotype/other/$namespace"), null, null, null, null)!!.use { assertEquals(0, it.count) }
            assertEquals(namespace, FolsomPromptFlagPolicy.NAMESPACE)
            // CAS refuses an outdated restore instead of overwriting another process's newer configuration.
            assertThrows(IllegalStateException::class.java) { resolver.call(uri, FolsomPhenotypeConfiguration.RESTORE_OVERRIDES,
                namespace, Bundle().apply { putBundle("overrides", original); putBundle("expected", Bundle()) }) }
            assertTrue(FolsomPhenotypeConfiguration.read(context).promptEnabled)
        } finally {
            val restored = resolver.call(uri, FolsomPhenotypeConfiguration.RESTORE_OVERRIDES, namespace,
                Bundle().apply { putBundle("overrides", original); putBundle("expected", expected) })!!
            assertTrue(restored.getBoolean("restored"))
            val reopened = resolver.call(uri, FolsomPhenotypeConfiguration.CAPTURE_OVERRIDES, namespace, null)!!.getBundle("overrides")!!
            assertEquals(original.keySet(), reopened.keySet())
            original.keySet().forEach { assertEquals(original.get(it), reopened.get(it)) }
        }
    }
}
