/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.R
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in UI check of the pages Passwords' Settings opens and the recovery pages its Recovery rows open, on a private
 * emulator only: no screen lock (owner presence, which this test can supply), no network (nothing reaches Google) and
 * one synthetic Google account added here and removed afterwards. Pages that load from Google show their failure state,
 * and recovery pages without a screen lock only say they need one; every page must still show Back, its title
 * and the account, go back, and lock again when left. Accessibility nodes are read across processes, so the
 * encryption page's own process is covered too. No real account, password or vault is read or changed.
 */
@RunWith(AndroidJUnit4::class)
class PasswordOwnerPagesAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val account = Account(ACCOUNT, "com.google")

    @Before fun fixture() {
        assumeTrue(arguments.getString("passwordOwnerPages") == OPT_IN)
        check(com.google.android.gms.BuildConfig.DEBUG && context.packageName == "com.google.android.gms")
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) { "Needs a device without screen lock" }
        check((context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork == null) { "Needs an offline device" }
        val accounts = AccountManager.get(context)
        if (account !in accounts.getAccountsByType("com.google")) check(accounts.addAccountExplicitly(account, null, null))
        accounts.setUserData(account, "GoogleUserId", USER_ID)
    }

    @After fun removeFixture() {
        if (arguments.getString("passwordOwnerPages") != OPT_IN || arguments.getString("passwordOwnerPagesKeep") == "1") return
        AccountManager.get(context).removeAccountExplicitly(account)
    }

    @Test fun everyPageShowsBackTitleAndAccountThenGoesBack() {
        for ((page, title) in PAGES + RECOVERY_PAGES) {
            open(page)
            node(text(title))
            node(ACCOUNT)
            click(text(R.string.cyclon_back))
            gone(text(title))
        }
    }

    @Test fun pagesLockAgainWhenLeftAndOpenAgainForTheOwner() {
        for ((page, title) in (PAGES + RECOVERY_PAGES).filter { it.first != PasswordEncryptionActivity::class.java }) {
            open(page)
            node(text(title))
            // The breach check waits for the owner's tap before it unlocks; the others ask at once.
            SystemClock.sleep(1000)
            if (find(text(R.string.passwords_open)) != null) click(text(R.string.passwords_open))
            gone(text(R.string.passwords_open))
            leave()
            open(page, back = true)
            node(text(R.string.passwords_no_lock_description))
            click(text(R.string.passwords_open))
            gone(text(R.string.passwords_open))
            click(text(R.string.cyclon_back))
            gone(text(title))
        }
    }

    /** The encryption page ends its owner session when left; it never reopens unlocked. */
    @Test fun encryptionPageClosesWhenLeft() {
        open(PasswordEncryptionActivity::class.java)
        node(text(R.string.passwords_encryption_manage))
        leave()
        gone(text(R.string.passwords_encryption_manage))
    }

    /** Offline, the encryption page shows its choices or its error; a choice asks first and Cancel changes nothing. */
    @Test fun encryptionChoiceAsksBeforeGoogleOpens() {
        open(PasswordEncryptionActivity::class.java)
        val choice = text(R.string.passwords_encryption_enable)
        waitFor { find(choice) != null || find(text(R.string.passwords_encryption_failed)) != null }
        assumeTrue("Encryption state unavailable offline", find(choice) != null)
        click(choice)
        node(text(R.string.passwords_encryption_enable_warning))
        node(text(R.string.passwords_encryption_continue))
        click(text(android.R.string.cancel))
        gone(text(R.string.passwords_encryption_enable_warning))
        node(choice)
        click(text(R.string.cyclon_back))
        gone(text(R.string.passwords_encryption_manage))
    }

    /** Opens [page], or with [back] returns to the instance already open, as the owner coming back to it. */
    private fun open(page: Class<*>, back: Boolean = false) {
        context.startActivity(Intent(context, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            if (back) Intent.FLAG_ACTIVITY_REORDER_TO_FRONT else 0)
            .putExtra("account", ACCOUNT).putExtra("google_user_id", USER_ID).putExtra("domain", "chromesync"))
    }
    private fun leave() {
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(1500)
    }
    private fun text(id: Int) = context.getString(id)
    // Only the window in front: a page closing behind another one must not answer for it. A dialog is its own window.
    private fun roots(): List<AccessibilityNodeInfo> = listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
    // Compose answers no findAccessibilityNodeInfosByText query; walk the tree as uiautomator does.
    private fun find(text: String): AccessibilityNodeInfo? = roots().firstNotNullOfOrNull { find(it, text) }
    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let { find(it, text) } }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timed out" }
            SystemClock.sleep(200)
        }
    }
    private fun node(text: String): AccessibilityNodeInfo { var found: AccessibilityNodeInfo? = null; waitFor { find(text)?.also { found = it } != null }; return found!! }
    private fun gone(text: String) = waitFor { find(text) == null }
    private fun click(text: String) {
        var target: AccessibilityNodeInfo? = node(text)
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Not clickable" }
    }

    companion object {
        const val OPT_IN = "cyc898-synthetic-offline"
        const val ACCOUNT = "cyc898-fixture@example.invalid"
        const val USER_ID = "cyc898-synthetic-user"
        const val TIMEOUT = 15_000L
        val PAGES = listOf(
            PasswordEncryptionActivity::class.java to R.string.passwords_encryption_manage,
            PasswordRecoveryEnrollmentActivity::class.java to R.string.passwords_recovery_enroll_phone,
            PasswordBreachActivity::class.java to R.string.passwords_breach_assess,
            SyncedPasskeyManagerActivity::class.java to R.string.passwords_synced_passkeys,
            PasswordSharingActivity::class.java to R.string.passwords_share,
            PasswordWifiSyncActivity::class.java to R.string.passwords_wifi_title,
        )
        // The pages Recovery's rows open (password recovery where a page serves both domains).
        val RECOVERY_PAGES = listOf(
            org.microg.gms.auth.folsom.ui.SecurityDomainLifecycleActivity::class.java to R.string.passwords_domain_manage,
            RecoverableVaultActivity::class.java to R.string.passwords_recoverable_vault_passwords,
            GpmPinRenewalActivity::class.java to R.string.passwords_pin_renewal_title,
            EnclavePinActivity::class.java to R.string.passwords_enclave_pin_title,
            RecoveryRestoreActivity::class.java to R.string.passwords_restore_passwords,
        )
    }
}
