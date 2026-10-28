/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.AtomicFile
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.credentials.provider.CallingAppInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.microg.gms.calendar.CalendarSyncActivity
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disposable stock-emulator component acceptance only. This invokes the internal broker directly;
 * it does not establish image-Core admission, focused-field ownership, controller or Jelly acceptance.
 * The host sets a private synthetic emulator PIN before creating any vault entries, then completes
 * the actual system credential screen (or presses Back for cancellation). This test never supplies
 * an authentication result, changes the device lock, or emits a password/PIN/credential payload.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class AgentSignInAuthenticationAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var step = "disposable-emulator-precondition"

    @Test fun realSystemAuthenticationDeliversOnce() = runCase(Case.CONFIRM)
    @Test fun cancelledSystemAuthenticationNeverDelivers() = runCase(Case.CANCEL)
    @Test fun expiredSystemAuthenticationNeverDelivers() = runCase(Case.EXPIRE)

    private enum class Case(val wire: String) { CONFIRM("confirm"), CANCEL("cancel"), EXPIRE("expire") }

    private fun runCase(case: Case) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passwordAcceptance") == OPT_IN)
        step = "disposable-emulator-precondition"
        try {
            requireDisposableEmulator()
            exercise(case)
        } catch (failure: Throwable) {
            // Native/semantics exceptions must not attach a credential, account or hierarchy.
            val frames = failure.stackTrace.take(8).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            throw AssertionError("Disposable system authentication failed at $step (${failure.javaClass.simpleName}); " +
                "frames=$frames; private contents withheld")
        }
    }

    private fun requireDisposableEmulator() {
        check(com.google.android.gms.BuildConfig.DEBUG && context.packageName == "com.google.android.gms")
        check(Application.getProcessName() == "com.google.android.gms")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(Build.FINGERPRINT.contains("generic", true) || Build.FINGERPRINT.contains("emulator", true) ||
            Build.FINGERPRINT.contains("/sdk_", true) && Build.FINGERPRINT.contains("/emu", true))
        check(Build.PRODUCT.contains("sdk", true) || Build.PRODUCT.contains("emu", true))
        val property = ProcessBuilder("/system/bin/getprop", "ro.kernel.qemu").start()
        check(property.inputStream.bufferedReader().use { it.readText().trim() } == "1")
        check(property.waitFor() == 0)
        check(AccountManager.get(context).getAccountsByType("com.google").isEmpty())
        // This lane is deliberately not an image/system-app acceptance claim.
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(keyguard.isDeviceSecure && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked)
    }

    // Synchronous, test-private callback: compare and wipe before deliver() wipes its own array.
    // Binder parceling is outside this component test and is covered separately by the probe.
    private class Completion : ResultReceiver(null) {
        val finished = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val deliveries = AtomicInteger()
        val matches = AtomicBoolean()
        val malformed = AtomicBoolean()
        val code = AtomicInteger(Int.MIN_VALUE)
        override fun onReceiveResult(resultCode: Int, data: Bundle?) {
            callbacks.incrementAndGet()
            code.set(resultCode)
            val password = data?.getCharArray("password")
            try {
                if (resultCode == 1) {
                    deliveries.incrementAndGet()
                    val expected = SEED.toCharArray()
                    try { matches.set(password != null && password.contentEquals(expected)) }
                    finally { expected.fill('\u0000') }
                    if (data?.keySet() != setOf("password")) malformed.set(true)
                } else if (resultCode != 0 || password != null) malformed.set(true)
            } finally {
                password?.fill('\u0000')
                // Cancellation uses Bundle.EMPTY, which must not be mutated.
                if (password != null) data?.remove("password")
                finished.countDown()
            }
        }
    }

    private fun exercise(case: Case) {
        step = "verify-installed-public-probe"
        val pm = context.packageManager
        val probe = pm.getPackageInfo(PROBE, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = probe.applicationInfo?.uid ?: error("Probe missing")
        check(uid >= 10_000 && pm.getPackagesForUid(uid)?.toSet() == setOf(PROBE))
        val signing = probe.signingInfo ?: error("Probe signer unavailable")
        check(signing.apkContentsSigners.size == 1)
        val certificate = MessageDigest.getInstance("SHA-256").digest(signing.apkContentsSigners.single().toByteArray())
        check(certificate.joinToString("") { "%02x".format(it.toInt() and 255) } == PROBE_CERTIFICATE)
        val realm = PasswordCaller.from(CallingAppInfo(PROBE, signing)).realms.single()
        val chrome = Proto.field(1, PasswordData.create(realm, realm, USERNAME, SEED, 0).encode())
        val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
        val vault = DevicePasswordVault(context)
        val access = AgentSignInAccess(context)
        val before = vault.list()
        try { check(before.isEmpty()) { "Disposable local vault must start empty" } }
        finally { before.forEach { it.chromeBytes.fill(0) } }
        val unrelatedGrants = unrelatedGrantSnapshot(tag)
        var inserted = false
        var handle: String? = null
        var scenario: ActivityScenario<CalendarSyncActivity>? = null
        val completion = Completion()
        try {
            step = "insert-owned-synthetic-login"
            inserted = true // Cleanup also reconciles an insertion whose outcome was interrupted.
            vault.insertIfAbsent(chrome)
            val initial = access.get("local", tag)
            access.setOwnerPolicy("local", tag, initial.incarnation, AgentSignInAccess.Level.ASK, true)
            val strict = access.get("local", tag)
            check(strict.requireDeviceAuthentication && strict.level == AgentSignInAccess.Level.ASK && strict.lastUsed == null)

            val target = Bundle().apply {
                putString("package", PROBE); putInt("uid", uid); putByteArray("certificate", certificate)
            }
            val broker = AgentSignInBroker(context)
            @Suppress("DEPRECATION")
            val candidates = broker.call("candidates", null, target).getParcelableArrayList<Bundle>("accounts") ?: error("No candidate")
            check(candidates.size == 1)
            val choice = candidates.single()
            check(choice.getString("username") == USERNAME && choice.getString("access") == "ask" && choice.getBoolean("require_authentication"))
            val ref = choice.getString("ref") ?: error("No reference")
            check(ref.matches(Regex("[a-f0-9]{64}")))
            // The empty calendar settings screen is an existing default-process Activity.
            // With no accounts and no clicks it performs no sync. It is only a foreground
            // anchor: PasswordManagerActivity lives in :ui and cannot share this broker map.
            // No background-launch exemption or fabricated onActivityResult is involved.
            step = "launch-foreground-anchor"
            val anchor = ActivityScenario.launch<CalendarSyncActivity>(Intent(context, CalendarSyncActivity::class.java))
            scenario = anchor
            waitForAnchorFocus(anchor)
            handle = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            val deadline = SystemClock.elapsedRealtime() + if (case == Case.EXPIRE) 15_000L else 110_000L
            val request = Bundle(target).apply {
                putString("handle", handle); putLong("deadline", deadline)
                putString("approval", "once"); putParcelable("result", completion)
            }
            step = "prepare-real-system-authentication"
            val result = broker.call("redeem", ref, request)
            @Suppress("DEPRECATION")
            val authentication = result.getParcelable<PendingIntent>("authentication") ?: error("No authentication pending intent")
            check(authentication.creatorPackage == context.packageName && authentication.creatorUid == context.applicationInfo.uid)
            check(authentication.isImmutable)
            check(completion.callbacks.get() == 0 && completion.deliveries.get() == 0)

            step = "send-authentication-pending-intent"
            anchor.onActivity { activity ->
                authentication.send(activity, 0, null, null, null, null,
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
            }
            step = "wait-for-real-system-credential-screen"
            val systemPackages = trustedCredentialPackages()
            val credentialWindowIds = waitForSystemCredentialScreen(completion, deadline, systemPackages)
            check(completion.callbacks.get() == 0 && completion.deliveries.get() == 0)
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("waiting_for_system_authentication", true)
                putString("authentication_case", case.wire)
            })
            step = "wait-for-owner-system-authentication-${case.wire}"
            check(completion.finished.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1) + 5_000, TimeUnit.MILLISECONDS))
            check(!completion.malformed.get() && completion.callbacks.get() == 1)
            if (case == Case.CONFIRM) {
                check(completion.code.get() == 1 && completion.deliveries.get() == 1 && completion.matches.get())
                check(access.get("local", tag).lastUsed != null)
            } else {
                check(completion.code.get() == 0 && completion.deliveries.get() == 0 && !completion.matches.get())
                check(access.get("local", tag).lastUsed == null)
            }
            step = "verify-system-authentication-window-dismissed-${case.wire}"
            waitForCredentialWindowDismissal(systemPackages, credentialWindowIds)
            step = "verify-one-shot-replay-refused"
            expectSecurityException { broker.call("redeem", ref, request) }
            var replayDenied = false
            try { authentication.send() } catch (_: PendingIntent.CanceledException) { replayDenied = true }
            check(replayDenied)
            instrumentation.waitForIdleSync()
            val expectedDeliveries = if (case == Case.CONFIRM) 1 else 0
            check(completion.callbacks.get() == 1 && completion.deliveries.get() == expectedDeliveries)
            val after = access.get("local", tag)
            check(after.requireDeviceAuthentication && after.level == AgentSignInAccess.Level.ASK && after.incarnation == strict.incarnation)
            check(AccountManager.get(context).getAccountsByType("com.google").isEmpty())
        } finally {
            val operationStep = step
            var cleaned = false
            step = "cleanup-owned-authentication-fixture"
            try { handle?.let(PasswordAgentFillRequests::cancel) }
            finally {
                try { scenario?.close() }
                finally {
                    try {
                        if (inserted) {
                            val entries = vault.list()
                            try {
                                val own = entries.singleOrNull { it.id == tag }
                                if (own != null) {
                                    check(MessageDigest.isEqual(own.chromeBytes, chrome))
                                    vault.deleteIfUnchanged(own.chromeBytes)
                                }
                            } finally { entries.forEach { it.chromeBytes.fill(0) } }
                        }
                        val remaining = vault.list()
                        try { check(remaining.isEmpty()) }
                        finally { remaining.forEach { it.chromeBytes.fill(0) } }
                        check(unrelatedGrantSnapshot(tag) == unrelatedGrants)
                        cleaned = true
                    } finally {
                        chrome.fill(0); certificate.fill(0)
                        if (cleaned) step = operationStep
                    }
                }
            }
        }
    }

    private fun waitForAnchorFocus(scenario: ActivityScenario<CalendarSyncActivity>) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            var focused = false
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            if (focused) return
            SystemClock.sleep(20)
        }
        error("Foreground anchor unavailable")
    }

    private fun trustedCredentialPackages(): Set<String> {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val challenge = keyguard.createConfirmDeviceCredentialIntent("", "") ?: error("System credential challenge unavailable")
        val resolved = context.packageManager.resolveActivity(challenge, 0) ?: error("System credential screen unresolved")
        val activity = resolved.activityInfo ?: error("System credential activity unavailable")
        // Android may present the actual PIN entry in a SystemUI BiometricPrompt instead
        // of the Settings activity that resolves the public credential-confirmation Intent.
        return setOf(activity.packageName, "com.android.systemui").onEach { pkg ->
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            check(info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            check(context.packageManager.checkSignatures("android", pkg) == PackageManager.SIGNATURE_MATCH)
        }
    }

    private data class AuthenticationWindows(
        val present: Set<Int>, val credential: Set<Int>, val authentication: Set<Int>, val rootsObserved: Boolean,
    )

    private fun waitForSystemCredentialScreen(completion: Completion, deadline: Long, systemPackages: Set<String>): Set<Int> {
        val end = minOf(deadline, SystemClock.elapsedRealtime() + 10_000)
        while (SystemClock.elapsedRealtime() < end) {
            check(completion.callbacks.get() == 0) { "Completion preceded real system challenge" }
            val windows = authenticationWindows(systemPackages)
            if (windows.credential.isNotEmpty()) return windows.authentication + windows.credential
            SystemClock.sleep(50)
        }
        error("Real system credential screen was not observed")
    }

    private fun waitForCredentialWindowDismissal(systemPackages: Set<String>, credentialWindowIds: Set<Int>) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var absentSince = 0L
        while (SystemClock.elapsedRealtime() < end) {
            val windows = authenticationWindows(systemPackages)
            val absent = windows.rootsObserved && windows.authentication.isEmpty() && windows.credential.isEmpty() &&
                credentialWindowIds.none { it in windows.present }
            if (!absent) absentSince = 0L
            else if (absentSince == 0L) absentSince = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - absentSince >= 750) return
            SystemClock.sleep(50)
        }
        error("System authentication window remained after terminal result")
    }

    private fun authenticationWindows(systemPackages: Set<String>): AuthenticationWindows {
        val automation = checkNotNull(instrumentation.uiAutomation) { "Instrumentation automation unavailable" }
        val serviceInfo = checkNotNull(automation.serviceInfo) { "Instrumentation accessibility unavailable" }
        val requiredFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        if (serviceInfo.flags and requiredFlags != requiredFlags) {
            serviceInfo.flags = serviceInfo.flags or requiredFlags
            automation.serviceInfo = serviceInfo
        }
        val present = mutableSetOf<Int>()
        val credential = mutableSetOf<Int>()
        val authentication = mutableSetOf<Int>()
        var rootsObserved = false
        fun inspect(root: AccessibilityNodeInfo) {
            rootsObserved = true
            present.add(root.windowId)
            if (root.packageName?.toString() !in systemPackages) return
            val traits = credentialTraits(root)
            if (traits.first) credential.add(root.windowId)
            if (traits.first || traits.second) authentication.add(root.windowId)
        }
        for (window in automation.windows.orEmpty()) {
            present.add(window.id)
            try {
                val root = window.root ?: continue
                try { inspect(root) } finally { @Suppress("DEPRECATION") root.recycle() }
            } finally { @Suppress("DEPRECATION") window.recycle() }
        }
        val active = automation.rootInActiveWindow
        if (active != null) {
            try { inspect(active) } finally { @Suppress("DEPRECATION") active.recycle() }
        }
        return AuthenticationWindows(present, credential, authentication, rootsObserved)
    }

    private fun credentialTraits(node: AccessibilityNodeInfo): Pair<Boolean, Boolean> {
        // Structure and fixed framework IDs only: never node text, descriptions, a PIN or a tree dump.
        val id = node.viewIdResourceName?.substringAfter(":id/")
        val visible = node.isVisibleToUser
        var credential = visible && (node.isPassword || node.isEditable && id in CREDENTIAL_INPUT_IDS ||
            node.className?.toString() == "com.android.internal.widget.LockPatternView" && id == "lockPattern")
        var authentication = visible && id in AUTHENTICATION_CONTAINER_IDS
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                val traits = credentialTraits(child)
                credential = credential || traits.first
                authentication = authentication || traits.second
            }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return credential to authentication
    }

    private fun unrelatedGrantSnapshot(tag: String): Map<String, String> {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val ownedKey = hash("vault:local").take(16) + ":" + hash("entry:local|$tag")
        val file = AtomicFile(File(context.noBackupFilesDir, "agent-signin-access-v1.json"))
        val data = try { file.readFully() } catch (_: FileNotFoundException) { return emptyMap() }
        return try {
            val all = JSONObject(String(data, Charsets.UTF_8))
            all.keys().asSequence().filter { it != ownedKey }.sorted().associateWith { all.get(it).toString() }
        } finally { data.fill(0) }
    }

    private fun expectSecurityException(action: () -> Unit) {
        try { action() } catch (_: SecurityException) { return }
        error("Replayed authentication request accepted")
    }

    private companion object {
        const val OPT_IN = "cyc788-disposable-auth"
        const val PROBE = "ai.cyclon.dev.passwordprobe"
        const val USERNAME = "cyc788-device-auth-only"
        const val SEED = "cyc788-public-device-authentication-only"
        const val PROBE_CERTIFICATE = "435044407117265d1dcc7769fcfd6092437791a31d561178574bb2398f41b516"
        val CREDENTIAL_INPUT_IDS = setOf("lockPassword", "password_entry", "pinEntry", "pin_entry", "auth_credential_input")
        val AUTHENTICATION_CONTAINER_IDS = setOf("biometric_prompt_constraint_layout", "auth_credential_input", "lockPassword", "lockPattern")
    }
}
