/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.R
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.passwords.PasswordOwnerActivity
import org.microg.gms.passwords.PasswordRepository
import org.microg.gms.passwords.VaultLockedException

/** Account-bound recovery is a foreground owner action. Password recovery never offers a reset. */
class GenericActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private lateinit var domain: String
    private var started = false
    private var passwordRecovery = false
    private val accounts get() = AccountManager.get(this)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val name = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        val selected = accounts.getAccountsByType("com.google").singleOrNull { it.name == name }
        val id = selected?.let { accounts.getUserData(it, "GoogleUserId") }
        val domain = intent.getStringExtra(EXTRA_SECURITY_DOMAIN)
        if (selected == null || id.isNullOrBlank() || domain.isNullOrBlank() || domain.length > 256) { finish(); return }
        account = selected; accountId = id; this.domain = domain
        passwordRecovery = intent.getBooleanExtra(EXTRA_PASSWORD_OWNER_RECOVERY, false)
        val expected = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (expected != null && expected != accountId || passwordRecovery && (domain != "chromesync" || expected.isNullOrBlank())) { finish(); return }
        supportFragmentManager.fragments.toList().forEach { supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        layout.addView(TextView(this).apply { setText(R.string.passwords_unlock_description) })
        layout.addView(Button(this).apply { setText(R.string.passwords_unlock); setOnClickListener { authenticateOwner() } })
        setContentView(layout)
        authenticateOwner()
    }
    internal fun requireRecoveryOwner() {
        requireOwner()
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId) throw VaultLockedException()
    }
    internal fun saveRecoveredKeys(keys: List<Keys>) {
        requireRecoveryOwner()
        if (passwordRecovery) {
            val material = keys.mapNotNull { it.keyMaterial?.toByteArray() }
            try { PasswordRepository(this, account).acceptTrustedVaultKeys(material, scheduleRefresh = false) }
            finally { material.forEach { it.fill(0) } }
        } else LocalKeyManager.getInstance(this).saveKeysForDomain(account.name, domain, keys, expectedUserId = accountId)
        requireRecoveryOwner()
    }
    override fun onOwnerAuthenticated() {
        try { requireRecoveryOwner() } catch (_: Exception) { finish(); return }
        if (started) return
        started = true
        supportFragmentManager.beginTransaction().replace(android.R.id.content, FolsomWebFragment.newInstance(
            account.name, domain, intent.getIntExtra(EXTRA_OPERATION, 0),
            intent.getStringExtra(EXTRA_SESSION_ID).orEmpty(),
            if (passwordRecovery) false else intent.getBooleanExtra(EXTRA_OFFER_RESET, false), accountId)).commit()
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onOwnerLocked() {
        if (started) {
            supportFragmentManager.fragments.filterIsInstance<FolsomWebFragment>().forEach { it.cancelRecovery() }
            finish()
        }
    }
    companion object {
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_SECURITY_DOMAIN = "security_domain"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_OFFER_RESET = "offer_reset"
        const val EXTRA_ACCOUNT_ID = "google_user_id"
        const val EXTRA_PASSWORD_OWNER_RECOVERY = "password_owner_recovery"
    }
}
