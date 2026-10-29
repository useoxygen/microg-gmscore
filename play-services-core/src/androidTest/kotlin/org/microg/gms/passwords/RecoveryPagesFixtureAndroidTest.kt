/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.R
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.folsom.DomainLifecycleOwnerStatus
import org.microg.gms.auth.folsom.ui.SecurityDomainLifecycleScreen
import org.microg.gms.cyclon.CyclonMaterialTheme

/**
 * Opt-in presentation check of the five recovery pages Recovery's rows open, with synthetic states only. Each state is
 * drawn by the page's own screen composable inside an idle Breach check page (which does nothing until the owner taps),
 * so no activity, client, account, key or request is involved and nothing reaches Google. The states stand in for
 * what Google would return: they show that rows, masked fields, actions and dialogs render and are wired, not that a
 * real recovery works. Pass `-e recoveryPagesState NAME -e holdSeconds N` to keep one state on screen, and
 * `-e recoveryPagesDialog 1` to open its question first.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPagesFixtureAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Before fun optIn() {
        assumeTrue(arguments.getString("recoveryPages") == OPT_IN)
        check(com.google.android.gms.BuildConfig.DEBUG && context.packageName == "com.google.android.gms")
    }

    /** Every state shows Back, its title, the account and its own lines; with [holdSeconds] one state stays up. */
    @Test fun everyStateRenders() {
        val only = arguments.getString("recoveryPagesState")
        for (state in STATES.filter { only == null || it.name == only }) show(state) {
            for (text in listOf(text(R.string.cyclon_back), ACCOUNT)) node(text, scroll = false)
            for (text in state.expected(context)) node(text)
            if (arguments.getString("recoveryPagesDialog") == "1") state.ask?.let { (action, warning) -> click(text(action)); node(text(warning)) }
            arguments.getString("holdSeconds")?.toLong()?.let { SystemClock.sleep(it * 1000) }
        }
    }

    /** The main action asks first; the question names the account, and Cancel leaves the page as it was. */
    @Test fun actionsAskBeforeAnything() {
        assumeTrue(arguments.getString("recoveryPagesState") == null)
        for (state in STATES.filter { it.ask != null }) show(state) {
            val (action, warning) = state.ask!!
            node(text(R.string.cyclon_back), scroll = false)
            click(context.getString(action))
            node(context.getString(warning))
            node(ACCOUNT)
            click(context.getString(android.R.string.cancel))
            gone(context.getString(warning))
            node(context.getString(action))
        }
    }

    private fun show(state: State, check: () -> Unit) {
        val host = instrumentation.startActivitySync(Intent(context, PasswordBreachActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PasswordBreachActivity
        try {
            instrumentation.runOnMainSync { host.setContent { CyclonMaterialTheme(glass = true) { state.content() } } }
            check()
        } catch (e: Throwable) { throw AssertionError("State ${state.name}", e) }
        finally { instrumentation.runOnMainSync { host.finish() }; instrumentation.waitForIdleSync() }
    }

    private class State(val name: String, val ask: Pair<Int, Int>? = null, val expected: (Context) -> List<String>,
                        val content: @Composable () -> Unit)

    private fun text(id: Int) = context.getString(id)
    // The window in front only; a dialog is its own window. Compose answers no text query, so walk the tree.
    private fun find(text: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }
    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let { find(it, text) } }
    }
    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timed out: $what" }
            SystemClock.sleep(200)
        }
    }
    // Compose reports no nodes outside the visible part of a page: once the page is up, sweep it down, then back up,
    // until [text] shows.
    private fun node(text: String, scroll: Boolean = true): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        var forward = true
        waitFor(text) {
            found = find(text)
            if (found == null && scroll) scrollable()?.let { page ->
                if (page.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    // Let the scroll settle, then read the page afresh: cached nodes can still show the old position.
                    SystemClock.sleep(400)
                    if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                } else forward = !forward
            }
            found != null
        }
        return found!!
    }
    private fun scrollable(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null || node.isScrollable) return node
        return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let { scrollable(it) } }
    }
    private fun gone(text: String) = waitFor("gone: $text") { find(text) == null }
    private fun click(text: String) {
        var target: AccessibilityNodeInfo? = node(text)
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Not clickable: $text" }
    }

    companion object {
        const val OPT_IN = "cyc900-synthetic"
        const val ACCOUNT = "cyc900-fixture@example.invalid"
        const val TIMEOUT = 15_000L
        private val digest = ByteArray(32) { it.toByte() }
        private val none: () -> Unit = {}
        private fun digits(value: String) = value.toCharArray()

        @Composable private fun vault(status: RecoverableVaultStatus?, failed: Boolean = false, unlocked: Boolean = true,
                                      domain: String = "chromesync") {
            var ask by remember { mutableStateOf<RecoverableVaultAction?>(null) }
            RecoverableVaultScreen(ACCOUNT, domain, unlocked, secure = true, busy = false, failed = failed, status = status,
                confirmation = ask, onBack = none, onUnlock = none, onAsk = { ask = it }, onRefresh = none, onConfirm = { ask = null })
        }

        @Composable private fun renewal(status: GpmPinRenewalStatus?, failed: Boolean = false) {
            var ask by remember { mutableStateOf<GpmPinRenewalAction?>(null) }
            GpmPinRenewalScreen(ACCOUNT, unlocked = true, secure = true, busy = false, failed = failed, status = status,
                confirmation = ask, onBack = none, onUnlock = none, onAsk = { ask = it }, onRefresh = none, onConfirm = { ask = null })
        }

        @Composable private fun enclave(status: EnclavePinStatus?, change: Boolean, arbitrary: Boolean, pin: String) {
            var ask by remember { mutableStateOf<EnclavePinAction?>(null) }
            EnclavePinScreen(ACCOUNT, unlocked = true, secure = true, busy = false, failed = false, status = status, change = change,
                arbitrary = arbitrary, pin = digits(pin), repeated = digits(pin), inputValid = pin.isNotEmpty(), confirmation = ask,
                onBack = none, onUnlock = none, onRefresh = none, onArbitrary = {}, onPin = {}, onRepeated = {},
                onAsk = { ask = it }, onConfirm = { ask = null })
        }

        private val backups = listOf(
            RecoveryRestoreChoice("synthetic-1", ByteArray(32) { 1 }, format = 1, remainingAttempts = 10, requestScopeDigest = digest),
            RecoveryRestoreChoice("synthetic-2", ByteArray(32) { 2 }, format = 2, remainingAttempts = 3, requestScopeDigest = digest),
            RecoveryRestoreChoice("synthetic-3", ByteArray(32) { 3 }, format = 3, remainingAttempts = null, requestScopeDigest = digest),
            RecoveryRestoreChoice("synthetic-4", ByteArray(32) { 4 }, format = 1, remainingAttempts = 5, requestScopeDigest = digest, vaultType = 3),
        )

        @Composable private fun restore(status: RecoveryRestoreStatus?, selected: Int?, secret: String, canFinish: Boolean = false,
                                        domain: String = "chromesync") {
            var ask by remember { mutableStateOf<RecoveryRestoreAction?>(null) }
            RecoveryRestoreScreen(ACCOUNT, domain, unlocked = true, secure = true, busy = false, failed = false, status = status,
                canFinish = canFinish, choices = if (status?.attemptId == null) backups else emptyList(),
                selected = selected?.let(backups::get), secret = digits(secret), confirmation = ask, onBack = none, onUnlock = none,
                onRefresh = none, onSelect = {}, onPatternView = {}, onPattern = {}, onSecret = {}, onAsk = { ask = it },
                onConfirm = { ask = null })
        }

        @Composable private fun domain(status: DomainLifecycleOwnerStatus?, requested: DomainLifecycleKind? = null,
                                       public: ByteArray? = null, message: Int? = null) {
            var ask by remember { mutableStateOf<DomainLifecycleKind?>(null) }
            var retry by remember { mutableStateOf(false) }
            var closing by remember { mutableStateOf(false) }
            SecurityDomainLifecycleScreen(ACCOUNT, "chromesync", requested, "com.example.synthetic", public, unlocked = true,
                secure = true, busy = false, message = message, status = status, confirmation = ask, retry = retry, closing = closing,
                onBack = none, onUnlock = none, onDomain = {}, onRefresh = none, onAsk = { ask = it }, onRetry = { retry = true },
                onClose = { closing = true }, onDismiss = { ask = null; retry = false; closing = false },
                onConfirm = { ask = null; retry = false; closing = false })
        }
        private fun members(count: Int, phase: DomainLifecyclePhase?, operation: DomainLifecycleKind? = null, epoch: Int = 4) =
            DomainLifecycleOwnerStatus("chromesync", epoch, count, digest, phase?.let { "synthetic-operation" }, operation, phase,
                authorityCleared = false)

        private val STATES = listOf(
            State("vault-locked", expected = { listOf(it.getString(R.string.passwords_recoverable_vault_passwords),
                it.getString(R.string.passwords_unlock)) }) { vault(null, unlocked = false) },
            State("vault-offline", expected = { listOf(it.getString(R.string.passwords_recoverable_vault_failed),
                it.getString(R.string.passwords_refresh)) }) { vault(null, failed = true) },
            State("vault-setup", ask = R.string.passwords_recoverable_vault_enable to R.string.passwords_recoverable_vault_confirm,
                expected = { listOf(it.getString(R.string.passwords_recoverable_vault_passkeys), it.getString(R.string.passwords_recoverable_vault_enable)) }) {
                vault(RecoverableVaultStatus("hw_protected", null, null, false), domain = "hw_protected")
            },
            State("vault-waiting", ask = R.string.passwords_recoverable_vault_continue to R.string.passwords_recoverable_vault_continue_confirm,
                expected = { listOf(it.getString(R.string.passwords_recoverable_vault_waiting),
                    it.getString(R.string.passwords_recovery_enroll_close_attempt)) }) {
                vault(RecoverableVaultStatus("chromesync", RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, "synthetic-attempt", true))
            },
            State("renewal-current", ask = R.string.passwords_pin_renewal_start to R.string.passwords_pin_renewal_confirm,
                expected = { listOf(it.getString(R.string.passwords_pin_renewal_title), it.getString(R.string.passwords_pin_renewal_current),
                    it.getString(R.string.passwords_pin_renewal_complete)) }) {
                renewal(GpmPinRenewalStatus("synthetic-renewal", GpmPinRenewalPhase.CONFIRMED, current = true))
            },
            State("renewal-uncertain", ask = R.string.passwords_recovery_enroll_close_attempt to R.string.passwords_pin_renewal_close_confirm,
                expected = { listOf(it.getString(R.string.passwords_recoverable_vault_uncertain), it.getString(R.string.passwords_pin_renewal_failed)) }) {
                renewal(GpmPinRenewalStatus("synthetic-renewal", GpmPinRenewalPhase.VAULT_LAUNCHED), failed = true)
            },
            State("pin-create", ask = R.string.passwords_enclave_pin_create_action to R.string.passwords_enclave_pin_confirm,
                expected = { listOf(it.getString(R.string.passwords_enclave_pin_title), it.getString(R.string.passwords_enclave_pin_new),
                    it.getString(R.string.passwords_enclave_pin_arbitrary)) }) {
                enclave(EnclavePinStatus(null, null, registered = true, hasWrappedCurrentKey = true, hasPin = false), change = false,
                    arbitrary = false, pin = "246810")
            },
            State("pin-change", expected = { listOf(it.getString(R.string.passwords_enclave_pin_change),
                it.getString(R.string.passwords_enclave_pin_complete)) }) {
                enclave(EnclavePinStatus(null, EnclavePinPhase.CONFIRMED, registered = true, hasWrappedCurrentKey = true, hasPin = true),
                    change = true, arbitrary = true, pin = "")
            },
            State("restore-choices", ask = R.string.passwords_restore_open to R.string.passwords_restore_confirm,
                expected = { listOf(it.getString(R.string.passwords_restore_passwords), it.getString(R.string.passwords_restore_backup, 1),
                    it.getString(R.string.passwords_restore_backup, 4), it.getString(R.string.passwords_restore_password)) }) {
                restore(RecoveryRestoreStatus(null, null, null), selected = 1, secret = "synthetic")
            },
            State("restore-pattern", expected = { listOf(it.getString(R.string.passwords_restore_passkeys),
                it.getString(R.string.passwords_restore_pattern_description)) }) {
                restore(RecoveryRestoreStatus(null, null, null), selected = 2, secret = "", domain = "hw_protected")
            },
            State("restore-recovered", ask = R.string.passwords_restore_finish to R.string.passwords_restore_finish_confirm,
                expected = { listOf(it.getString(R.string.passwords_restore_ready)) }) {
                restore(RecoveryRestoreStatus(RecoveryRestorePhase.RECOVERED, "synthetic-attempt", null, digest), selected = null,
                    secret = "", canFinish = true)
            },
            State("domain-members", ask = R.string.passwords_domain_reset to R.string.passwords_domain_reset_warning,
                expected = { listOf(it.getString(R.string.passwords_domain_manage), it.getString(R.string.passwords_domain_passkeys),
                    it.resources.getString(R.string.passwords_domain_devices, 3), it.getString(R.string.passwords_domain_enroll),
                    it.getString(R.string.passwords_domain_rotate)) }) {
                domain(members(3, DomainLifecyclePhase.COMMITTED, DomainLifecycleKind.ENROLL))
            },
            State("domain-pending", ask = R.string.passwords_retry_owner to R.string.passwords_domain_retry_warning,
                expected = { listOf(it.getString(R.string.passwords_domain_uncertain), it.getString(R.string.passwords_domain_close_attempt)) }) {
                domain(members(2, DomainLifecyclePhase.INDETERMINATE, DomainLifecycleKind.ROTATE))
            },
            State("domain-new", ask = R.string.passwords_domain_create to R.string.passwords_domain_create_warning,
                expected = { listOf(it.getString(R.string.passwords_domain_create)) }) {
                domain(members(0, null, epoch = 0), message = R.string.passwords_domain_changed)
            },
            State("domain-add-device", ask = R.string.passwords_domain_add to R.string.passwords_domain_add_warning,
                expected = { listOf(it.getString(R.string.passwords_domain_add), it.getString(R.string.passwords_domain_passwords)) }) {
                domain(members(3, null), requested = DomainLifecycleKind.ADD_MEMBER, public = ByteArray(65) { 7 })
            },
        )
    }
}
