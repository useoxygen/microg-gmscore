/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Debug fallback only. Native vault/file locks and account reads never block the owner UI thread. */
class PasswordAcceptanceAccountActivity : PasswordOwnerActivity() {
    private var request: PasswordAcceptanceAccounts.Selection? = null
    private var chosen: PasswordAcceptanceAccounts.Selected? = null
    private val generation = AtomicLong()
    private val live = AtomicBoolean()
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        request = runCatching { LegacyPasskeyTicketStore.system(this); PasswordAcceptanceAccounts.consume(intent.getStringExtra("request")) }.getOrNull()
        if (request == null) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        authenticateOwner()
    }
    override fun onOwnerAuthenticated() {
        requireOwner(); live.set(true)
        val epoch = generation.incrementAndGet()
        lifecycleScope.launch {
            try {
                val current = request ?: throw VaultLockedException()
                val accounts = withContext(Dispatchers.IO) {
                    val primary = if (current.role == "family") PasswordAcceptanceAccounts.resolve(this@PasswordAcceptanceAccountActivity, current.run, "primary").accountId else null
                    PasswordAcceptanceAccounts.candidates(this@PasswordAcceptanceAccountActivity).filter { it.accountId != primary &&
                        (current.role != "primary" || PasswordAcceptanceAccounts.approvedFixture(this@PasswordAcceptanceAccountActivity, it)) }
                }
                requireOwner(); check(live.get() && generation.get() == epoch)
                val column = LinearLayout(this@PasswordAcceptanceAccountActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
                column.addView(TextView(this@PasswordAcceptanceAccountActivity).apply { text = if (current.role == "primary")
                    "Select the approved synthetic password account" else "Select the second synthetic Google family account" })
                column.addView(TextView(this@PasswordAcceptanceAccountActivity).apply { text = "Account details stay on this device. Select only a synthetic test account. This selection authorizes the bounded acceptance fixture; personal accounts must remain unselected." })
                accounts.forEach { selected -> column.addView(Button(this@PasswordAcceptanceAccountActivity).apply {
                    text = selected.account.name
                    setOnClickListener {
                        if (!busy) { requireOwner(); chosen = selected; text = selected.account.name + " ✓" }
                    }
                }) }
                column.addView(Button(this@PasswordAcceptanceAccountActivity).apply { text = "Confirm synthetic account"; setOnClickListener {
                    if (!busy) {
                        busy = true
                        lifecycleScope.launch {
                            try {
                                requireOwner(); check(live.get() && generation.get() == epoch)
                                val selected = chosen ?: throw VaultLockedException()
                                withContext(Dispatchers.IO) { PasswordAcceptanceAccounts.save(this@PasswordAcceptanceAccountActivity, current.run, current.role, selected) {
                                    check(live.get() && generation.get() == epoch)
                                } }
                                requireOwner(); check(live.get() && generation.get() == epoch)
                                current.reply.complete(true); finish()
                            } catch (_: Exception) {
                                withContext(NonCancellable + Dispatchers.IO) { runCatching {
                                    PasswordAcceptanceAccounts.clearRole(this@PasswordAcceptanceAccountActivity, current.run, current.role)
                                } }
                                current.reply.complete(false); finish()
                            } finally { busy = false }
                        }
                    }
                } })
                column.addView(Button(this@PasswordAcceptanceAccountActivity).apply { text = "Cancel"; setOnClickListener { current.reply.complete(false); finish() } })
                setContentView(ScrollView(this@PasswordAcceptanceAccountActivity).apply { addView(column) })
            } catch (_: Exception) { request?.reply?.complete(false); finish() }
        }
    }
    override fun onPause() { live.set(false); generation.incrementAndGet(); super.onPause() }
    override fun onOwnerAuthenticationFailed() { request?.reply?.complete(false); finish() }
    override fun onOwnerLocked() { live.set(false); generation.incrementAndGet(); chosen = null }
    override fun onDestroy() { live.set(false); generation.incrementAndGet(); request?.reply?.complete(false); chosen = null; super.onDestroy() }
}
