/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*
import java.util.concurrent.atomic.AtomicBoolean

class PasswordBreachActivity : PasswordOwnerActivity() {
    private val owner = AtomicBoolean(false)
    @Volatile private var client: PasswordBreachClient? = null
    @Volatile private var activeCancellation: AtomicBoolean? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var progress by mutableStateOf<LeakCheckProgress?>(null)
    private var result by mutableStateOf<LeakCheckAssessment?>(null)
    private var error by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme {
            PasswordOwnerPage(stringResource(R.string.passwords_breach_assess), intent.getStringExtra("account"), onBack = { cancel(); finish() }) {
                if (!unlocked) { PasswordOwnerLocked(hasSecureScreenLock, onUnlock = ::authenticateOwner); return@PasswordOwnerPage }
                CyclonNote(stringResource(R.string.passwords_breach_description))
                if (busy) {
                    progress?.let { value ->
                        CyclonVoice(if (value.saving) stringResource(R.string.passwords_breach_saving)
                            else stringResource(R.string.passwords_breach_progress, value.checked, value.total),
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Cyclon.palette.ink)
                    }
                    CyclonProgress(true, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    PasswordOwnerActions { CyclonLineButton(stringResource(android.R.string.cancel), onClick = ::cancel) }
                } else PasswordOwnerActions { CyclonInkButton(stringResource(R.string.passwords_breach_start), onClick = ::start) }
                result?.let { value ->
                    CyclonHairline(Modifier.padding(top = 8.dp))
                    PasswordOwnerText(stringResource(R.string.passwords_breach_result, value.checked, value.leaked), Modifier.padding(top = 8.dp))
                    if (value.knownLeaked != 0) PasswordOwnerText(stringResource(R.string.passwords_breach_known, value.knownLeaked))
                    if (value.unassessed != 0) PasswordOwnerText(stringResource(R.string.passwords_breach_unassessed, value.unassessed))
                }
                error?.let { PasswordOwnerText(stringResource(it)) }
            }
        } }
    }
    private fun start() {
        requireOwner()
        if (busy) return
        result = null; error = null; progress = null
        val identity = try {
            val name = intent.getStringExtra("account") ?: throw VaultLockedException()
            val userId = intent.getStringExtra("google_user_id") ?: throw VaultLockedException()
            val account = Account(name, "com.google")
            val accounts = AccountManager.get(this)
            if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != userId) throw VaultLockedException()
            account to userId
        } catch (_: Exception) { error = R.string.passwords_breach_failed; return }
        val stopped = AtomicBoolean(false)
        activeCancellation = stopped; busy = true
        lifecycleScope.launch {
            try {
                val assessment = withContext(Dispatchers.IO) {
                    if (stopped.get() || !owner.get()) throw LeakCheckCancelledException()
                    val current = PasswordBreachClient(this@PasswordBreachActivity,
                        PasswordRepository(this@PasswordBreachActivity, identity.first), identity.second) {
                        if (stopped.get()) throw LeakCheckCancelledException()
                        if (!owner.get()) throw VaultLockedException()
                    }
                    if (stopped.get() || activeCancellation !== stopped) { current.cancel(); throw LeakCheckCancelledException() }
                    client = current
                    current.assess { value ->
                        runOnUiThread { if (owner.get() && activeCancellation === stopped) progress = value }
                    }
                }
                if (stopped.get()) throw LeakCheckCancelledException()
                requireOwner()
                if (activeCancellation === stopped) result = assessment
            } catch (_: LeakCheckCancelledException) {
                if (owner.get() && activeCancellation === stopped) error = R.string.passwords_breach_cancelled
            } catch (_: Exception) {
                if (owner.get() && activeCancellation === stopped) error = R.string.passwords_breach_failed
            } finally { if (activeCancellation === stopped) { busy = false; progress = null; client = null; activeCancellation = null } }
        }
    }
    private fun cancel() { activeCancellation?.set(true); client?.cancel() }
    override fun onOwnerAuthenticated() { owner.set(true); unlocked = true }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { owner.set(false); cancel(); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (unlocked) owner.set(runCatching { requireOwner(); true }.getOrDefault(false))
    }
    override fun onOwnerLocked() {
        owner.set(false); cancel(); client = null; activeCancellation = null
        unlocked = false; busy = false; result = null; progress = null; error = null
    }
}
