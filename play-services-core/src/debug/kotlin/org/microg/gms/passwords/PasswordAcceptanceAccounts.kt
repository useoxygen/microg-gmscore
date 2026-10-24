/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Debug-only native selection. Host arguments carry a run label/role, never account names or Gaia IDs. */
internal object PasswordAcceptanceAccounts {
    const val REALM = "https://cyc651.example.invalid/"
    const val USERNAME = "cyc651-synthetic-user"
    class Selected(val account: Account, val accountId: String) {
        override fun toString() = "PasswordAcceptanceAccount[redacted]"
    }
    class Selection(val run: String, val role: String, val reply: CompletableDeferred<Boolean>)
    private val requests = ConcurrentHashMap<String, Selection>()
    fun checked(run: String, role: String) {
        check(run.length <= 36 && runCatching { UUID.fromString(run).toString() == run }.getOrDefault(false))
        check(role in setOf("primary", "family"))
    }
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).also { check(it >= 0) }
    private fun store(context: Context, run: String, role: String, authorize: () -> Unit = {}): AndroidPasskeyStore {
        checked(run, role); return PasswordAcceptanceStorage.store(context, "selection/$run/$role", authorize)
    }
    fun candidates(context: Context): List<Selected> {
        val manager = AccountManager.get(context)
        return manager.getAccountsByType("com.google").mapNotNull { account ->
            manager.getUserData(account, "GoogleUserId")?.takeIf(String::isNotBlank)?.let { Selected(account, it) }
        }.take(20)
    }
    fun approvedFixture(context: Context, selected: Selected): Boolean = runCatching {
        verify(context, selected)
        // Refuse accounts without an already enabled, populated local vault before constructing a repository.
        val existing = AndroidVaultStore(context, "google:" + selected.accountId) { verify(context, selected) }
        if (!existing.enabled || existing.locked { it.load().metadata == null }) return@runCatching false
        val repository = repository(context, selected)
        repository.list(false).any { it.data.realm == REALM && it.data.username == USERNAME &&
            !it.data.blocked && it.data.password.isNotEmpty() }
    }.getOrDefault(false)
    fun verify(context: Context, selected: Selected) {
        val manager = AccountManager.get(context)
        check(selected.account.type == "com.google" && manager.getAccountsByType("com.google").contains(selected.account) &&
            manager.getUserData(selected.account, "GoogleUserId") == selected.accountId)
    }
    fun save(context: Context, run: String, role: String, selected: Selected, authorize: () -> Unit = {}) {
        authorize()
        checked(run, role); verify(context, selected)
        if (role == "primary") check(approvedFixture(context, selected))
        if (role == "family") check(resolve(context, run, "primary").accountId != selected.accountId)
        val now = SystemClock.elapsedRealtime(); val currentBoot = boot(context)
        store(context, run, role) { authorize(); verify(context, selected) }.locked { session ->
            verify(context, selected)
            session.save(PasswordAcceptanceSelection(selected.account.name, selected.accountId, now, currentBoot, run, role).encode())
        }
    }
    fun resolve(context: Context, run: String, role: String): Selected = store(context, run, role).locked { session ->
        val raw = session.load()
        try {
            val receipt = PasswordAcceptanceSelection.decode(raw)
            val manager = AccountManager.get(context)
            val actual = manager.getAccountsByType("com.google").singleOrNull { it.name == receipt.name }
            receipt.verify(run, role, boot(context), SystemClock.elapsedRealtime(), actual?.name,
                actual?.let { manager.getUserData(it, "GoogleUserId") })
            Selected(actual ?: throw VaultLockedException(), receipt.accountId).also { verify(context, it) }
        } finally { raw.fill(0) }
    }
    fun repository(context: Context, selected: Selected, expectedParent: String? = null): PasswordRepository {
        return PasswordAcceptanceSelection.capture({ verify(context, selected) }) {
            PasswordRepository(context, selected.account, expectedParent)
        }
    }
    fun optionalRepository(context: Context, arguments: Bundle): PasswordRepository? = arguments.getString("passwordAcceptanceRun")?.let {
        repository(context, resolve(context, it, "primary"))
    }
    fun tryAutomatic(context: Context, run: String, role: String): Boolean {
        checked(run, role)
        val primary = if (role == "family") resolve(context, run, "primary").accountId else null
        val selected = candidates(context).filter { it.accountId != primary && approvedFixture(context, it) }.singleOrNull() ?: return false
        save(context, run, role, selected); return true
    }
    fun picker(context: Context, run: String, role: String): Pair<Intent, CompletableDeferred<Boolean>> {
        checked(run, role)
        val token = UUID.randomUUID().toString(); val reply = CompletableDeferred<Boolean>()
        check(requests.size < 32 && requests.putIfAbsent(token, Selection(run, role, reply)) == null)
        reply.invokeOnCompletion { requests.remove(token) }
        return Intent(context, PasswordAcceptanceAccountActivity::class.java).putExtra("request", token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to reply
    }
    fun consume(token: String?): Selection = requests.remove(token) ?: throw VaultLockedException()
    fun clearRole(context: Context, run: String, role: String) { store(context, run, role).locked { it.save(byteArrayOf()) } }
    fun clear(context: Context, run: String) { for (role in listOf("primary", "family")) clearRole(context, run, role) }
}
