/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Only PasswordAgentFillProvider's authenticated Core caller can reach this native broker. */
internal class AgentSignInBroker(private val context: Context) {
    private val accounts = AccountManager.get(context)
    private val access = AgentSignInAccess(context)
    private class Choice(val account: Account?, val userId: String?, val tag: String, val incarnation: String) {
        val vaultKey get() = AgentSignInAccess.vaultKey(userId)
        override fun toString() = "SignInChoice[redacted]"
    }

    fun call(method: String, ref: String?, input: Bundle?): Bundle {
        requireTargetReady(input?.getBoolean("background", false) == true)
        return when (method) {
            "candidates" -> {
                if (ref != null) throw SecurityException()
                candidates(PasswordAgentFillTarget.from(context, input ?: throw SecurityException()))
            }
            "set_access" -> {
                requireUnlocked()
                val choice = choice(ref)
                val level = AgentSignInAccess.Level.of(input?.getString("level")) ?: throw SecurityException()
                withChoice(choice) {
                    requireUnlocked()
                    access.setCurrent(choice.vaultKey, choice.tag, choice.incarnation, level)
                }
                Bundle.EMPTY
            }
            "redeem" -> {
                val choice = choice(ref)
                val extras = input ?: throw SecurityException()
                val approval = extras.getString("approval")
                if (approval !in setOf("once", "always")) throw SecurityException()
                val request = PasswordAgentFillRequest.from(context, extras)
                val vault = vault(choice)
                val before = vault.list().singleOrNull { it.id == choice.tag } ?: throw SecurityException()
                if (!PasswordAffiliationMatcher(context, vault).canFill(before.data, request.realm)) throw SecurityException()
                // Capture only metadata in a pending authentication request, never a PasswordView.
                val realm = before.data.realm
                val username = before.data.username
                val grant = withChoice(choice, vault) {
                    val state = access.get(choice.vaultKey, choice.tag)
                    if (state.level == AgentSignInAccess.Level.NEVER) throw SecurityException()
                    state
                }
                val requiresAuthentication = grant.requireDeviceAuthentication
                if (!AgentSignInLockPolicy.deliveryAllowed(locked(), request.background, approval == "once", requiresAuthentication) ||
                    locked() && grant.level != AgentSignInAccess.Level.ALWAYS) throw SecurityException()
                if (requiresAuthentication && !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
                    throw SecurityException()
                }
                claimHandle(request.handle, request.deadline)
                PasswordAgentFillRequests.put(request)
                if (requiresAuthentication) {
                    try {
                        val authentication = PasswordAgentAuthenticationRequests.prepare(context, request, username) { authenticated ->
                            deliver(choice, request, realm, username, once = true, authenticated = authenticated)
                        }
                        Bundle().apply { putParcelable("authentication", authentication) }
                    } catch (failure: Exception) {
                        PasswordAgentFillRequests.cancel(request.handle)
                        throw failure
                    }
                } else {
                    try { deliver(choice, request, realm, username, approval == "once") }
                    finally { PasswordAgentFillRequests.cancel(request.handle) }
                    Bundle.EMPTY
                }
            }
            else -> throw SecurityException()
        }
    }

    private fun deliver(choice: Choice, request: PasswordAgentFillRequest, realm: String, username: String,
        once: Boolean, authenticated: (() -> Boolean)? = null) {
        withChoice(choice) { current ->
            // The earlier affiliation proof covers exactly this entry's current realm.
            if (current.realm != realm || current.username != username || current.blocked || current.password.isEmpty()) throw SecurityException()
            access.redeem(choice.vaultKey, choice.tag, choice.incarnation, once, deviceAuthenticated = authenticated?.invoke() == true) {
                requireTargetReady(request.background)
                if (!AgentSignInLockPolicy.deliveryAllowed(locked(), request.background, once, authenticated != null)) throw SecurityException()
                // Authentication is native request-local state, rechecked immediately before handover.
                if (authenticated != null && !authenticated() || !request.valid(context)) throw SecurityException()
                request.deliver(context, current.password.toCharArray())
            }
        }
    }

    private fun candidates(target: PasswordAgentFillTarget): Bundle {
        val rows = arrayListOf<Bundle>()
        val owners: List<Account?> = listOf(null) + DeviceAccount.visible(context, accounts.getAccountsByType("com.google")).take(20)
        for (account in owners) {
            // A locked/disabled account must not prevent using a separate available vault.
            try {
                val userId = stableId(account)
                val vault = vault(account, userId)
                if (!vault.enabled) continue
                val matcher = PasswordAffiliationMatcher(context, vault)
                for (entry in vault.list()) {
                    if (rows.size >= 200) break
                    if (entry.data.username.length > 1000 || !matcher.canFill(entry.data, target.realm)) continue
                    val initial = Choice(account, userId, entry.id, "")
                    withChoice(initial, vault, checkIncarnation = false) { current ->
                        if (current.realm != entry.data.realm || current.username != entry.data.username ||
                            current.blocked || current.password.isEmpty()) return@withChoice
                        requireTargetReady(target.background)
                        if (!target.valid(context)) throw SecurityException()
                        val state = access.get(initial.vaultKey, entry.id)
                        val choice = Choice(account, userId, entry.id, state.incarnation)
                        val ref = remember(choice)
                        rows.add(Bundle().apply {
                            putString("ref", ref); putString("username", current.username)
                            putString("account", account?.name ?: context.getString(com.google.android.gms.R.string.passwords_on_device))
                            putString("access", state.level.wire)
                            putBoolean("require_authentication", state.requireDeviceAuthentication)
                            state.lastUsed?.let { putLong("last_used", it) }
                        })
                    }
                }
            } catch (_: Exception) { /* Account disabled, removed or locked while enumerating. */ }
        }
        if (!target.valid(context)) throw SecurityException()
        requireTargetReady(target.background)
        return Bundle().apply { putParcelableArrayList("accounts", rows); putBoolean("background_signin_v1", true) }
    }

    private fun requireUnlocked() {
        requireTargetReady(false)
    }
    private fun requireTargetReady(background: Boolean) {
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: throw SecurityException()
        if (!AgentSignInLockPolicy.targetReady(users.isUserUnlocked, locked(), background)) throw SecurityException()
    }
    private fun locked(): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: throw SecurityException()
        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    }
    private fun stableId(account: Account?): String? {
        if (account == null) return null
        if (account !in accounts.getAccountsByType("com.google")) throw SecurityException()
        return accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw SecurityException()
    }
    private fun vault(choice: Choice) = vault(choice.account, choice.userId)
    private fun vault(account: Account?, userId: String?): PasswordVault {
        if (stableId(account) != userId) throw SecurityException()
        return account?.let { PasswordRepository(context, it) } ?: DevicePasswordVault(context)
    }
    private fun <T> withChoice(choice: Choice, vault: PasswordVault = vault(choice), checkIncarnation: Boolean = true,
        action: (PasswordData) -> T): T {
        fun apply(data: PasswordData): T {
            if (stableId(choice.account) != choice.userId || !vault.enabled) throw SecurityException()
            if (checkIncarnation && access.get(choice.vaultKey, choice.tag).incarnation != choice.incarnation) throw SecurityException()
            return action(data)
        }
        return when (vault) {
            is PasswordRepository -> vault.withAgentEntry(choice.tag, ::apply)
            is DevicePasswordVault -> vault.withAgentEntry(choice.tag, ::apply)
            else -> throw SecurityException()
        }
    }

    companion object {
        private val key = ByteArray(32).also(SecureRandom()::nextBytes)
        private val choices = LinkedHashMap<String, Choice>()
        private val handles = LinkedHashMap<String, Long>()
        @Synchronized private fun remember(choice: Choice): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            val raw = Proto.field(1, choice.vaultKey) + Proto.field(2, choice.tag) + Proto.field(3, choice.incarnation)
            val ref = mac.doFinal(raw).joinToString("") { "%02x".format(it) }
            choices[ref] = choice
            while (choices.size > 1024) choices.remove(choices.keys.first())
            return ref
        }
        @Synchronized private fun choice(ref: String?): Choice = choices[ref] ?: throw SecurityException()
        @Synchronized private fun claimHandle(handle: String, deadline: Long) {
            val now = SystemClock.elapsedRealtime()
            handles.entries.removeAll { it.value <= now }
            if (handles.containsKey(handle) || handles.size >= 1024) throw SecurityException()
            handles[handle] = deadline
        }
    }
}
