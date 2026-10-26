/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.credentials.provider.CallingAppInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Explicit local-only probe cleanup. No account lookup, provider call, network or caller-selected credential. */
@RunWith(AndroidJUnit4::class)
class PasswordLocalProbeCleanupAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun exactLocalProbeCredentialIsAbsentBeforeFrameworkSave() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passwordAcceptance") == OPT_IN)
        var entries = emptyList<PasswordView>()
        try {
            val identity = probeIdentity()
            entries = DevicePasswordVault(context).list()
            verify(identity)
            check(entries.none { it.data.realm == identity.realm && it.data.username == USERNAME })
            instrumentation.sendStatus(2, Bundle().apply { putBoolean("exactLocalProbeAbsent", true) })
        } catch (_: Exception) {
            throw AssertionError("Exact local probe absence preflight did not pass")
        } finally { entries.forEach { it.chromeBytes.fill(0) } }
    }

    @Test fun deleteOnlyExactLocalProbeCredentialAndPreserveUnrelatedEntries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passwordAcceptance") == OPT_IN)
        var before = emptyList<PasswordView>()
        var after = emptyList<PasswordView>()
        var beforeDigest: ByteArray? = null
        var afterDigest: ByteArray? = null
        try {
            val identity = probeIdentity()
            // This constructor is the actual account=null local vault, never a Google repository.
            val vault = DevicePasswordVault(context)
            before = vault.list()
            verify(identity)
            check(before.map { it.id }.toSet().size == before.size)
            val matching = before.filter { it.data.realm == identity.realm && it.data.username == USERNAME }
            check(matching.size <= 1)
            val selected = matching.singleOrNull()
            if (selected != null) {
                val expectedTag = PasswordData.create(identity.realm, identity.realm, USERNAME, SEED, 0).tag()
                check(selected.id == expectedTag && selected.data.origin == identity.realm &&
                    !selected.data.blocked && selected.data.password == SEED)
            }
            beforeDigest = unrelatedDigest(before, selected?.id)
            verify(identity)
            // Atomic optimistic comparison refuses a changed payload. There is no broad-delete fallback.
            selected?.let { vault.deleteIfUnchanged(it.chromeBytes) }
            after = vault.list()
            verify(identity)
            check(after.none { it.data.realm == identity.realm && it.data.username == USERNAME })
            afterDigest = unrelatedDigest(after, null)
            check(MessageDigest.isEqual(beforeDigest, afterDigest))
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("exactLocalProbeAbsent", true)
                putBoolean("unrelatedLocalEntriesUnchanged", true)
                putInt("exactLocalProbeDeletedCount", if (selected == null) 0 else 1)
            })
        } catch (_: Exception) {
            // Never attach native exception text, credential payloads, realm, account or digest values.
            throw AssertionError("Exact local probe cleanup did not pass; no broader deletion was attempted")
        } finally {
            beforeDigest?.fill(0); afterDigest?.fill(0)
            before.forEach { it.chromeBytes.fill(0) }; after.forEach { it.chromeBytes.fill(0) }
        }
    }

    private data class ProbeIdentity(val uid: Int, val realm: String)

    private fun probeIdentity(): ProbeIdentity {
        check(com.google.android.gms.BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 34)
        check(context.packageName == "com.google.android.gms")
        val manager = context.packageManager
        val installed = manager.getPackageInfo(PROBE, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = installed.applicationInfo?.uid ?: throw SecurityException()
        check(uid >= 10_000 && manager.getPackagesForUid(uid)?.toSet() == setOf(PROBE))
        val signing = installed.signingInfo ?: throw SecurityException()
        val signers = signing.apkContentsSigners
        check(signers.size == 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
        val actual = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == DEBUG_CERT_SHA256)
        // Use the same installed-signing-identity Android realm producer as framework credential save.
        val realm = PasswordCaller.from(CallingAppInfo(PROBE, signing)).realms.single()
        return ProbeIdentity(uid, realm)
    }

    private fun verify(expected: ProbeIdentity) { check(probeIdentity() == expected) }

    private fun unrelatedDigest(entries: List<PasswordView>, excludedId: String?): ByteArray {
        check(entries.map { it.id }.toSet().size == entries.size)
        val digest = MessageDigest.getInstance("SHA-256")
        entries.filter { it.id != excludedId }.sortedBy { it.id }.forEach { entry ->
            val id = entry.id.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(id.size).array()); digest.update(id)
            digest.update(ByteBuffer.allocate(4).putInt(entry.chromeBytes.size).array()); digest.update(entry.chromeBytes)
        }
        return digest.digest()
    }

    private companion object {
        const val OPT_IN = "cyc680-local-probe-cleanup"
        const val PROBE = "ai.cyclon.dev.passwordprobe"
        const val USERNAME = "cyc680-native-fill-synthetic"
        const val SEED = "cyc680-synthetic-native-fill-only"
        // Public signer from password-probe-isolated.apk, verified on the host with apksigner.
        const val DEBUG_CERT_SHA256 = "435044407117265d1dcc7769fcfd6092437791a31d561178574bb2398f41b516"
    }
}
