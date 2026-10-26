/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.isDialog as isComposeDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.credentials.provider.CallingAppInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.R
import com.google.android.gms.credential.manager.EXTRA_KEY_ACCOUNT_NAME
import com.google.android.gms.credential.manager.PasswordManagerActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit local-only acceptance. Native cases use isolated real Android Keystore/SQLite storage.
 * UI cases require the pre-existing, signer-verified public passwordprobe fixture, never a Google
 * vault. They intentionally leave only that fixture's access at the requested level for the next
 * Core acceptance step; they neither create nor delete it. No credential content enters results.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AgentSignInAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var step = "precondition"

    @Test fun nativeGrantPersistsAndRevocationStopsAlreadyOpenedReader() = localOnly {
        isolatedVault { identity, vault ->
            val chrome = synthetic("first")
            try {
                vault.insertIfAbsent(chrome)
                val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
                val access = AgentSignInAccess(context) { 1234L }
                val initial = access.get(identity, tag)
                check(initial.level == AgentSignInAccess.Level.ASK && initial.lastUsed == null)
                var deliveries = 0
                denied { access.redeem(identity, tag, initial.incarnation, false) { deliveries++ } }
                access.redeem(identity, tag, initial.incarnation, true) { deliveries++ }
                check(deliveries == 1 && access.get(identity, tag).lastUsed == 1234L)
                check(access.get(identity, tag).level == AgentSignInAccess.Level.ASK)
                access.setCurrent(identity, tag, initial.incarnation, AgentSignInAccess.Level.ALWAYS)
                val reopened = AgentSignInAccess(context)
                check(reopened.get(identity, tag).level == AgentSignInAccess.Level.ALWAYS)
                check(reopened.get(identity, tag).incarnation == initial.incarnation)
                reopened.redeem(identity, tag, initial.incarnation, false) { deliveries++ }
                access.setCurrent(identity, tag, initial.incarnation, AgentSignInAccess.Level.ASK)
                denied { reopened.redeem(identity, tag, initial.incarnation, false) { deliveries++ } }
                access.setCurrent(identity, tag, initial.incarnation, AgentSignInAccess.Level.NEVER)
                denied { reopened.redeem(identity, tag, initial.incarnation, true) { deliveries++ } }
                check(deliveries == 2)
                // Reopen the real encrypted vault as well as the durable grant file.
                val entries = DevicePasswordVault(context, identity).list()
                try { check(entries.size == 1 && entries.single().id == tag) }
                finally { entries.forEach { it.chromeBytes.fill(0) } }
            } finally { chrome.fill(0) }
        }
    }

    @Test fun nativeVaultDeleteRecreateAndClearRevokeLoginIncarnations() = localOnly {
        isolatedVault { identity, vault ->
            val first = synthetic("first")
            val other = synthetic("other")
            var edited: ByteArray? = null
            try {
                vault.insertIfAbsent(first); vault.insertIfAbsent(other)
                val originalData = PasswordData.decode(Proto.decode(first).one(1))
                val tag = originalData.tag()
                val otherTag = PasswordData.decode(Proto.decode(other).one(1)).tag()
                val access = AgentSignInAccess(context)
                val original = access.get(identity, tag)
                val untouched = access.get(identity, otherTag)
                access.setCurrent(identity, tag, original.incarnation, AgentSignInAccess.Level.ALWAYS)
                access.setCurrent(identity, otherTag, untouched.incarnation, AgentSignInAccess.Level.NEVER)
                val changed = Proto.field(1, originalData.withPassword("cyc779-public-edited-fixture", 1).encode())
                edited = changed
                vault.editIfUnchanged(first, changed)
                check(access.get(identity, tag).incarnation == original.incarnation)
                check(access.get(identity, tag).level == AgentSignInAccess.Level.ALWAYS)
                vault.deleteIfUnchanged(changed)
                vault.insertIfAbsent(first)
                val replacement = AgentSignInAccess(context).get(identity, tag)
                check(replacement.incarnation != original.incarnation)
                check(replacement.level == AgentSignInAccess.Level.ASK && replacement.lastUsed == null)
                denied { access.redeem(identity, tag, original.incarnation, true) { error("Stale delivery") } }
                denied { access.setCurrent(identity, tag, original.incarnation, AgentSignInAccess.Level.ALWAYS) }
                check(access.get(identity, otherTag).incarnation == untouched.incarnation)
                check(access.get(identity, otherTag).level == AgentSignInAccess.Level.NEVER)
                vault.store.clear()
                val afterClear = AgentSignInAccess(context).get(identity, otherTag)
                check(afterClear.incarnation != untouched.incarnation)
                check(afterClear.level == AgentSignInAccess.Level.ASK && afterClear.lastUsed == null)
                denied { access.redeem(identity, otherTag, untouched.incarnation, true) { error("Cleared delivery") } }
            } finally { first.fill(0); other.fill(0); edited?.fill(0) }
        }
    }

    @Test fun localProbeAllowSignInSavedThroughOwnerUi() = probeUi(AgentSignInAccess.Level.ALWAYS)
    @Test fun localProbeAskEachTimeSavedThroughOwnerUi() = probeUi(AgentSignInAccess.Level.ASK)
    @Test fun localProbeNeverSavedThroughOwnerUi() = probeUi(AgentSignInAccess.Level.NEVER)
    @Test fun localProbeCancelLeavesAccessUnchanged() = probeUi(null)

    private fun localOnly(action: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passwordAcceptance") == OPT_IN)
        step = "precondition"
        try {
            check(com.google.android.gms.BuildConfig.DEBUG && context.packageName == "com.google.android.gms")
            action()
        } catch (failure: Throwable) {
            // Compose assertions may otherwise attach a semantics tree containing private entries.
            throw AssertionError("Local-only sign-in acceptance failed at $step (${failure.javaClass.simpleName}); credential contents withheld")
        }
    }

    private fun isolatedVault(action: (String, DevicePasswordVault) -> Unit) {
        // A synthetic identity activates AndroidVaultStore's production grant invalidation hook;
        // no Account/PasswordRepository is constructed and no actual Google user ID is consulted.
        val identity = "google:cyc779-isolated-${UUID.randomUUID()}"
        val vault = DevicePasswordVault(context, identity)
        try { step = "native-storage"; action(identity, vault) }
        finally {
            val operationStep = step
            step = "isolated-storage-cleanup"
            try { vault.store.clear() }
            finally { AgentSignInAccess(context).retain(identity, emptySet()) }
            check(vault.list().isEmpty())
            step = operationStep
        }
    }

    private fun synthetic(suffix: String): ByteArray = Proto.field(1, PasswordData.create(
        "https://cyc779.example.invalid/", "https://cyc779.example.invalid/", "cyc779-public-$suffix",
        "cyc779-public-fixture", 0).encode())

    private fun denied(action: () -> Unit) {
        try { action() } catch (_: SecurityException) { return }
        error("Revoked operation was accepted")
    }

    private fun probeUi(requested: AgentSignInAccess.Level?) = localOnly {
        step = "owner-authentication-precondition"
        check(Application.getProcessName() == "com.google.android.gms:ui")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure) {
            step = "owner-credential-authentication-required-and-unavailable"
            error("Owner credential authentication is required; this test cannot supply it")
        }
        step = "device-must-already-be-unlocked"
        check(!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked)
        step = "verify-exact-probe"
        val realm = probeRealm()
        val vault = DevicePasswordVault(context)
        val access = AgentSignInAccess(context)
        val before = vault.list()
        var after = emptyList<PasswordView>()
        var beforeDigest: ByteArray? = null
        var afterDigest: ByteArray? = null
        var original: AgentSignInAccess.State? = null
        var targetId: String? = null
        var completed = false
        var others = emptyMap<String, AgentSignInAccess.State>()
        try {
            // The manager enumerates transfer recovery rows. Refuse a run that could purge or
            // interact with somebody else's pending transfer as a side effect of opening it.
            check(AndroidVaultStore(context, "owner-password-transfers-v1").locked { it.load().ownerTransfers.isEmpty() })
            val target = exactProbe(before, realm)
            targetId = target.id
            beforeDigest = recordDigest(before)
            val starting = access.get("local", target.id)
            original = starting
            others = before.filter { it.id != target.id }.associate { it.id to access.get("local", it.id) }
            // A nonexistent explicit request prevents the manager's sole-account default from
            // opening an ambient Google vault. Only the following On this device tap selects storage.
            val intent = Intent(context, PasswordManagerActivity::class.java)
                .putExtra(EXTRA_KEY_ACCOUNT_NAME, "cyc779-no-account-${UUID.randomUUID()}.invalid")
            step = "launch-owner-manager"
            ActivityScenario.launch<PasswordManagerActivity>(intent).use { scenario ->
                step = "wait-owner-manager-focus"
                waitForFocus(scenario)
                step = "tap-owner-open"
                tap(compose.onNodeWithText(context.getString(R.string.passwords_open)))
                step = "wait-local-storage-choice"
                waitForOneNode(hasText(context.getString(R.string.passwords_on_device)))
                step = "tap-local-storage-choice"
                tap(compose.onNodeWithText(context.getString(R.string.passwords_on_device)))
                step = "wait-local-search-field"
                waitForOneNode(hasSetTextAction())
                step = "filter-local-probe"
                compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(USERNAME)
                closeSoftKeyboard()
                step = "wait-filtered-probe-row"
                // Search now also contains USERNAME. Count the displayed credential row only;
                // otherwise a correctly loaded result and the editable query make two matches.
                waitForOneNode(hasText(USERNAME) and !hasSetTextAction())
                val settingsTab = hasText(context.getString(R.string.passwords_tab_settings)) and hasClickAction()
                val passwordsTab = hasText(context.getString(R.string.passwords_tab_passwords)) and hasClickAction()
                step = "tap-local-settings-tab"
                tap(compose.onNode(settingsTab).performScrollTo())
                step = "wait-local-settings-tab-selected"
                waitForOneNode(settingsTab and isSelected())
                step = "assert-settings-hides-search"
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                step = "tap-return-to-passwords-tab"
                // The page heading is also "Passwords". Only the tab has a click action.
                tap(compose.onNode(passwordsTab).performScrollTo())
                step = "wait-passwords-tab-selected"
                waitForOneNode(passwordsTab and isSelected())
                step = "wait-returned-passwords-search"
                waitForOneNode(hasSetTextAction())
                step = "assert-returned-search-preserves-query"
                compose.onNode(hasSetTextAction() and hasText(USERNAME)).assertExists()
                step = "verify-tab-navigation-preserves-data"
                val navigated = vault.list()
                var navigatedDigest: ByteArray? = null
                try {
                    exactProbe(navigated, realm)
                    navigatedDigest = recordDigest(navigated)
                    check(MessageDigest.isEqual(beforeDigest, navigatedDigest))
                    (others + (target.id to starting)).forEach { (tag, state) ->
                        val current = access.get("local", tag)
                        check(current.level == state.level && current.incarnation == state.incarnation && current.lastUsed == state.lastUsed)
                    }
                } finally {
                    navigatedDigest?.fill(0)
                    navigated.forEach { it.chromeBytes.fill(0) }
                }
                step = "open-verified-probe-detail"
                tap(compose.onNode(hasText(USERNAME) and !hasSetTextAction()).performScrollTo())
                step = "verify-probe-detail"
                compose.onNodeWithText(context.getString(R.string.passwords_back_to_passwords)).assertExists()
                compose.onNodeWithText(USERNAME).assertExists()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                step = "open-sign-in-dialog"
                val currentLabel = context.getString(R.string.passwords_assistant_access_status, context.getString(label(starting.level)))
                tap(compose.onNodeWithText(currentLabel).performScrollTo())
                compose.onNode(hasText(context.getString(R.string.passwords_assistant_access)) and
                    hasAnyAncestor(isComposeDialog())).assertExists()
                verifySecureDialog(scenario)
                val selected = requested ?: if (starting.level == AgentSignInAccess.Level.ALWAYS)
                    AgentSignInAccess.Level.NEVER else AgentSignInAccess.Level.ALWAYS
                step = "choose-sign-in-access"
                tap(compose.onNodeWithText(context.getString(label(selected))).performScrollTo(), dialog = true)
                if (requested == null) {
                    tap(compose.onNodeWithText(context.getString(android.R.string.cancel)), dialog = true)
                } else if (requested == starting.level) {
                    // No-op Save is intentionally disabled. Still test a real selection and Save
                    // by switching through another allowed level within this one fixture only.
                    val intermediate = if (requested == AgentSignInAccess.Level.ALWAYS)
                        AgentSignInAccess.Level.ASK else AgentSignInAccess.Level.ALWAYS
                    tap(compose.onNodeWithText(context.getString(label(intermediate))).performScrollTo(), dialog = true)
                    saveAndWait(target.id, intermediate)
                    tap(compose.onNodeWithText(context.getString(R.string.passwords_assistant_access_status,
                        context.getString(label(intermediate)))).performScrollTo())
                    tap(compose.onNodeWithText(context.getString(label(requested))).performScrollTo(), dialog = true)
                    saveAndWait(target.id, requested)
                } else saveAndWait(target.id, requested)
                step = "verify-ui-persistence"
                val expected = requested ?: starting.level
                compose.onNodeWithText(context.getString(R.string.passwords_assistant_access_status,
                    context.getString(label(expected)))).assertExists()
                val persisted = AgentSignInAccess(context).get("local", target.id)
                check(persisted.level == expected && persisted.incarnation == starting.incarnation && persisted.lastUsed == starting.lastUsed)
                check(probeRealm() == realm)
            }
            completed = true
        } finally {
            val operationStep = step
            var verified = false
            step = "verify-unrelated-state"
            try {
                after = vault.list()
                val currentTarget = exactProbe(after, realm)
                // Successful Save cases deliberately leave their requested value for Core tests.
                // On failure, restore only this verified fixture's still-current incarnation.
                val starting = original
                if (!completed && starting != null && targetId == currentTarget.id) {
                    check(access.get("local", currentTarget.id).incarnation == starting.incarnation)
                    access.setCurrent("local", currentTarget.id, starting.incarnation, starting.level)
                }
                beforeDigest?.let {
                    afterDigest = recordDigest(after)
                    check(MessageDigest.isEqual(it, afterDigest))
                }
                others.forEach { (tag, state) ->
                    val current = access.get("local", tag)
                    check(current.level == state.level && current.incarnation == state.incarnation && current.lastUsed == state.lastUsed)
                }
                verified = true
            } finally {
                if (verified) step = operationStep
                beforeDigest?.fill(0); afterDigest?.fill(0)
                before.forEach { it.chromeBytes.fill(0) }; after.forEach { it.chromeBytes.fill(0) }
            }
        }
    }

    private fun waitForOneNode(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            // Screen loading or a tab transition can briefly detach the semantics root. Missing roots are
            // not readiness; keep polling. Other failures and the timeout still propagate.
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun saveAndWait(tag: String, level: AgentSignInAccess.Level) {
        step = "save-sign-in-access"
        tap(compose.onNodeWithText(context.getString(R.string.passwords_save)), dialog = true)
        compose.waitUntil(10_000) { AgentSignInAccess(context).get("local", tag).level == level &&
            compose.onAllNodes(isComposeDialog()).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitForFocus(scenario: ActivityScenario<PasswordManagerActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var since = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            if (!focused) since = 0L
            else if (since == 0L) since = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - since >= 750) return
            Thread.sleep(20)
        }
        error("Owner manager did not receive input focus")
    }

    private fun tap(node: SemanticsNodeInteraction, dialog: Boolean = false) {
        val bounds = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
        val target = onView(withId(android.R.id.content))
        (if (dialog) target.inRoot(isDialog()) else target).perform(GeneralClickAction(Tap.SINGLE,
            CoordinatesProvider { view ->
                val location = IntArray(2); view.rootView.getLocationOnScreen(location)
                floatArrayOf(bounds.center.x + location[0], bounds.center.y + location[1])
            }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun verifySecureDialog(scenario: ActivityScenario<PasswordManagerActivity>) = scenario.onActivity { activity ->
        check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        val windows = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<DialogWindowProvider>().map { it.window }.distinct().toList()
        check(windows.size == 1)
        check(windows.single().attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        check(windows.single().callback is PasswordDialogTouchGuard)
    }

    private fun probeRealm(): String {
        check(Build.VERSION.SDK_INT >= 34)
        val manager = context.packageManager
        val installed = manager.getPackageInfo(PROBE, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = installed.applicationInfo?.uid ?: throw SecurityException()
        check(uid >= 10_000 && manager.getPackagesForUid(uid)?.toSet() == setOf(PROBE))
        val signing = installed.signingInfo ?: throw SecurityException()
        val signers = signing.apkContentsSigners
        check(signers.size == 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
        try { check(digest.joinToString("") { "%02x".format(it.toInt() and 255) } == DEBUG_CERT_SHA256) }
        finally { digest.fill(0) }
        return PasswordCaller.from(CallingAppInfo(PROBE, signing)).realms.single()
    }

    private fun exactProbe(entries: List<PasswordView>, realm: String): PasswordView {
        check(entries.map { it.id }.toSet().size == entries.size)
        val target = entries.single { it.data.realm == realm && it.data.username == USERNAME }
        check(target.id == PasswordData.create(realm, realm, USERNAME, SEED, 0).tag())
        check(target.data.origin == realm && !target.data.blocked && target.data.password == SEED)
        // The search is a substring match: refuse an ambiguous result before opening a control.
        check(entries.count { !it.data.blocked && it.data.matchesSearch(USERNAME) } == 1)
        return target
    }

    private fun recordDigest(entries: List<PasswordView>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.id }.forEach { entry ->
            val id = entry.id.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(id.size).array()); digest.update(id)
            digest.update(ByteBuffer.allocate(4).putInt(entry.chromeBytes.size).array()); digest.update(entry.chromeBytes)
        }
        return digest.digest()
    }

    private fun label(level: AgentSignInAccess.Level) = when (level) {
        AgentSignInAccess.Level.ASK -> R.string.passwords_assistant_ask
        AgentSignInAccess.Level.ALWAYS -> R.string.passwords_assistant_always
        AgentSignInAccess.Level.NEVER -> R.string.passwords_assistant_never
    }

    private companion object {
        const val OPT_IN = "cyc779-local-only"
        const val PROBE = "ai.cyclon.dev.passwordprobe"
        const val USERNAME = "cyc680-native-fill-synthetic"
        const val SEED = "cyc680-synthetic-native-fill-only"
        const val DEBUG_CERT_SHA256 = "435044407117265d1dcc7769fcfd6092437791a31d561178574bb2398f41b516"
    }
}
