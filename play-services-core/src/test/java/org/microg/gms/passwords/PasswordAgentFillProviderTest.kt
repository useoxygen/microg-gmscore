/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowPendingIntent
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.security.MessageDigest

/** Model a remote ContentProvider Binder caller; Robolectric's normal Binder shadow does not clear its fake UID. */
@Implements(Binder::class)
class PasswordFillBinderIdentityShadow : ShadowBinder() {
    companion object {
        var clears = 0
        var restores = 0
        @JvmStatic @Implementation fun clearCallingIdentity(): Long {
            val prior = Binder.getCallingUid()
            clears++
            ShadowBinder.setCallingUid(Process.myUid())
            return prior.toLong()
        }
        @JvmStatic @Implementation fun restoreCallingIdentity(token: Long) {
            restores++
            ShadowBinder.setCallingUid(token.toInt())
        }
    }
}

/** Reproduce AMS's creator-package UID check at the real PendingIntent factory boundary. */
@Implements(PendingIntent::class)
class PasswordFillPendingIntentShadow : ShadowPendingIntent() {
    companion object {
        var failCreation = false
        var creationUid: Int? = null
        @JvmStatic @Implementation(methodName = "getActivity")
        fun createActivity(context: Context, code: Int, intent: Intent, flags: Int): PendingIntent {
            creationUid = Binder.getCallingUid()
            if (creationUid != Process.myUid()) throw SecurityException("creator package does not belong to remote UID")
            if (failCreation) throw IllegalStateException("synthetic PendingIntent creation failure")
            return ReflectionHelpers.callStaticMethod(ShadowPendingIntent::class.java, "getActivity",
                ClassParameter.from(Context::class.java, context), ClassParameter.from(Int::class.javaPrimitiveType!!, code),
                ClassParameter.from(Intent::class.java, intent), ClassParameter.from(Int::class.javaPrimitiveType!!, flags))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [PasswordFillBinderIdentityShadow::class, PasswordFillPendingIntentShadow::class])
class PasswordAgentFillProviderTest {
    private val core = "ai.cyclon.core"
    private val services = "com.google.android.gms"
    private val target = "ai.cyclon.dev.passwordprobe"
    private val coreUid = 12345
    private val targetUid = 12346
    private val handle = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
    private val signature = Signature("01234567")
    private lateinit var context: Context
    private lateinit var provider: PasswordAgentFillProvider
    private var cancelled = 0

    @Before fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName(): String = services
        }
        installCore(system = true)
        val info = PackageInfo().apply {
            packageName = target
            applicationInfo = ApplicationInfo().apply { packageName = target; uid = targetUid }
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signature)) }
        }
        shadowOf(context.packageManager).installPackage(info)
        ShadowBinder.setCallingUid(coreUid)
        PasswordFillBinderIdentityShadow.clears = 0
        PasswordFillBinderIdentityShadow.restores = 0
        PasswordFillPendingIntentShadow.failCreation = false
        PasswordFillPendingIntentShadow.creationUid = null
        provider = PasswordAgentFillProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = "com.google.android.gms.cyclon.password_fill" })
        }
    }
    @After fun cleanup() {
        ShadowBinder.setCallingUid(coreUid)
        PasswordAgentFillRequests.cancel(handle)
        ShadowBinder.reset()
    }
    private fun installCore(system: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = core
            applicationInfo = ApplicationInfo().apply {
                packageName = core; uid = coreUid; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
        shadowOf(context.packageManager).setPackagesForUid(coreUid, core)
    }
    private fun input() = Bundle().apply {
        putString("handle", handle); putString("package", target); putInt("uid", targetUid)
        putByteArray("certificate", MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
        putLong("deadline", SystemClock.elapsedRealtime() + 60_000)
        putParcelable("result", object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { if (resultCode == 0) cancelled++ }
        })
    }
    @Suppress("DEPRECATION")
    private fun prepare(): PendingIntent = provider.call("prepare", null, input()).getParcelable("picker")!!

    @Test fun authenticatedCoreCreatesServicesPickerAsOwnUidThenRestoresTheRemoteCaller() {
        val picker = prepare()
        assertEquals(Process.myUid(), PasswordFillPendingIntentShadow.creationUid)
        assertEquals(coreUid, Binder.getCallingUid())
        assertEquals(1, PasswordFillBinderIdentityShadow.clears)
        assertEquals(1, PasswordFillBinderIdentityShadow.restores)
        val saved = shadowOf(picker)
        assertTrue(saved.isActivity)
        assertTrue(saved.isImmutable)
        assertEquals(PendingIntent.FLAG_ONE_SHOT, saved.flags and PendingIntent.FLAG_ONE_SHOT)
        assertEquals(services, saved.savedIntent.component!!.packageName)
        assertEquals(PasswordAgentFillActivity::class.java.name, saved.savedIntent.component!!.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, saved.savedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals("microg-password-agent:/fill/$handle", saved.savedIntent.data.toString())
        assertNull(saved.savedIntent.extras)
    }
    @Test fun wrongUidNonSystemCoreAndSharedUidAreRefusedBeforeIdentityIsCleared() {
        ShadowBinder.setCallingUid(targetUid)
        assertThrows(SecurityException::class.java) { prepare() }
        ShadowBinder.setCallingUid(coreUid)
        installCore(system = false)
        assertThrows(SecurityException::class.java) { prepare() }
        installCore(system = true)
        shadowOf(context.packageManager).setPackagesForUid(coreUid, core, "untrusted.shared.package")
        assertThrows(SecurityException::class.java) { prepare() }
        assertEquals(0, PasswordFillBinderIdentityShadow.clears)
        assertEquals(0, PasswordFillBinderIdentityShadow.restores)
        assertEquals(coreUid, Binder.getCallingUid())
    }
    @Test fun creationFailureRestoresIdentityAndRevokesOnlyItsNewRequestSoANewAttemptCanPrepare() {
        PasswordFillPendingIntentShadow.failCreation = true
        assertThrows(IllegalStateException::class.java) { prepare() }
        assertEquals(coreUid, Binder.getCallingUid())
        assertEquals(1, PasswordFillBinderIdentityShadow.clears)
        assertEquals(1, PasswordFillBinderIdentityShadow.restores)
        assertEquals(1, cancelled)
        assertThrows(SecurityException::class.java) { PasswordAgentFillRequests.find(Uri.parse("microg-password-agent:/fill/$handle")) }
        PasswordFillPendingIntentShadow.failCreation = false
        assertNotNull(prepare())
        assertEquals(coreUid, Binder.getCallingUid())
        assertEquals(2, PasswordFillBinderIdentityShadow.clears)
        assertEquals(2, PasswordFillBinderIdentityShadow.restores)
    }
    @Test fun cancellationBeforeRegistrationRevokesLatePrepare() {
        provider.call("cancel", handle, null)
        assertThrows(SecurityException::class.java) { prepare() }
    }

    @Test fun arbitraryAppsCannotAssertWebOrigins() {
        val bundle = input().apply { putString("web_origin", "https://example.com") }
        assertThrows(SecurityException::class.java) { PasswordAgentFillTarget.from(context, bundle) }
    }

    @Test fun jellyOriginRequiresInstalledSystemIdentityAndRejectsPathsAndUserInfo() {
        val jelly = "org.lineageos.jelly"
        fun install(system: Boolean) {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = jelly
                applicationInfo = ApplicationInfo().apply {
                    packageName = jelly; uid = targetUid; flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
                signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signature)) }
            })
        }
        val bundle = input().apply { putString("package", jelly); putString("web_origin", "https://example.com") }
        install(false)
        assertThrows(SecurityException::class.java) { PasswordAgentFillTarget.from(context, bundle) }
        install(true)
        assertEquals("https://example.com/", PasswordAgentFillTarget.from(context, bundle).realm)
        for (origin in listOf("http://example.com", "https://example.com/login", "https://owner@example.com", "https://example.com?x=1")) {
            bundle.putString("web_origin", origin)
            assertThrows(SecurityException::class.java) { PasswordAgentFillTarget.from(context, bundle) }
        }
        bundle.putString("web_origin", "https://example.com")
        val target = PasswordAgentFillTarget.from(context, bundle)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = jelly
            applicationInfo = ApplicationInfo().apply { packageName = jelly; uid = targetUid; flags = ApplicationInfo.FLAG_SYSTEM }
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("abcdef01"))) }
        })
        assertFalse(target.valid(context))
    }

    private fun authentication(fill: PasswordAgentFillRequest, use: (() -> Boolean) -> Unit = { fail("Must await native authentication") }): PendingIntent {
        val identity = Binder.clearCallingIdentity()
        return try { PasswordAgentAuthenticationRequests.prepare(context, fill, use = use) }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    @Test fun strictAuthenticationHasNoUnlockedOrNoScreenLockFallback() {
        val lock = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        lock.setIsDeviceSecure(false)
        lock.setIsDeviceLocked(false)
        lock.setKeyguardLocked(false)
        val request = PasswordAgentFillRequest.from(context, input())
        assertThrows(SecurityException::class.java) { authentication(request) }
        lock.setIsDeviceSecure(true)
        lock.setIsDeviceLocked(true)
        assertThrows(SecurityException::class.java) { authentication(request) }
        lock.setIsDeviceLocked(false)
        lock.setKeyguardLocked(true)
        assertThrows(SecurityException::class.java) { authentication(request) }
    }

    @Test fun strictAuthenticationIsImmutableOneShotRequestBoundAndCancelledWithFill() {
        val lock = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        lock.setIsDeviceSecure(true)
        lock.setIsDeviceLocked(false)
        lock.setKeyguardLocked(false)
        val request = PasswordAgentFillRequest.from(context, input())
        PasswordAgentFillRequests.put(request)
        val pendingIntent = authentication(request)
        val pending = shadowOf(pendingIntent)
        assertTrue(pending.isImmutable)
        assertEquals(PendingIntent.FLAG_ONE_SHOT, pending.flags and PendingIntent.FLAG_ONE_SHOT)
        assertEquals(services, pending.savedIntent.component!!.packageName)
        assertEquals(PasswordAgentAuthenticationActivity::class.java.name, pending.savedIntent.component!!.className)
        assertNull(pending.savedIntent.extras)
        assertEquals("microg-password-agent:/authenticate/$handle", pending.savedIntent.data.toString())
        val nativeRequest = PasswordAgentAuthenticationRequests.find(pending.savedIntent.data)
        assertSame(request, nativeRequest.fill)
        assertEquals(target, nativeRequest.target)
        assertSame(request, PasswordAgentFillRequests.find(Uri.parse("microg-password-agent:/fill/$handle")))
        assertTrue(PasswordAgentAuthenticationRequests.claim(nativeRequest))
        assertFalse(PasswordAgentAuthenticationRequests.claim(nativeRequest))
        provider.call("cancel", handle, null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(request.valid(context))
        assertFalse(PasswordAgentAuthenticationRequests.claim(nativeRequest))
        assertThrows(SecurityException::class.java) { PasswordAgentAuthenticationRequests.find(pending.savedIntent.data) }
    }

    @Test fun strictAuthenticationExpiresWithoutEverReturningPassword() {
        val lock = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        lock.setIsDeviceSecure(true)
        lock.setIsDeviceLocked(false)
        lock.setKeyguardLocked(false)
        val request = PasswordAgentFillRequest.from(context, input())
        PasswordAgentFillRequests.put(request)
        val pendingIntent = authentication(request)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        assertFalse(request.valid(context))
        assertThrows(SecurityException::class.java) { PasswordAgentAuthenticationRequests.find(shadowOf(pendingIntent).savedIntent.data) }
        assertEquals(1, cancelled)
    }

}
