/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.content.Context
import androidx.annotation.RequiresApi
import androidx.credentials.provider.*

@RequiresApi(34)
object PasswordProvider {
    fun get(context: Context, option: BeginGetPasswordOption, request: BeginGetCredentialRequest): List<CredentialEntry> {
        val caller = PasswordCaller.from(request.callingAppInfo ?: return emptyList())
        val tickets = PasswordRequestTickets(context)
        return vaults(context).flatMap { (account, repo) ->
            runCatching {
                if (!repo.enabled) emptyList() else repo.list().filter { entry ->
                    caller.realms.any(entry.data::canFillRealm) &&
                        (option.allowedUserIds.isEmpty() || entry.data.username in option.allowedUserIds)
                }.take(100).map { entry ->
                    PasswordCredentialEntry(context = context, username = entry.data.username,
                        displayName = entry.data.displayName.takeIf { it.isNotEmpty() },
                        pendingIntent = tickets.issue(account, entry.id, entry.data.realm, caller.identity, false),
                        beginGetPasswordOption = option, isAutoSelectAllowed = false)
                }
            }.getOrDefault(emptyList())
        }
    }
    fun create(context: Context, request: BeginCreatePasswordCredentialRequest): BeginCreateCredentialResponse {
        val caller = PasswordCaller.from(request.callingAppInfo ?: throw SecurityException())
        val tickets = PasswordRequestTickets(context)
        return BeginCreateCredentialResponse.Builder().apply {
            AccountManager.get(context).getAccountsByType("com.google").forEach { account ->
                val repo = runCatching { PasswordRepository(context, account) }.getOrNull()
                if (repo?.enabled == true && repo.store.locked { it.load().metadata != null }) {
                    addCreateEntry(CreateEntry(account.name,
                        tickets.issue(account, null, caller.realms.single(), caller.identity, true)))
                }
            }
            addCreateEntry(CreateEntry(context.getString(com.google.android.gms.R.string.passwords_on_device),
                tickets.issue(null, null, caller.realms.single(), caller.identity, true)))
        }.build()
    }
    private fun vaults(context: Context): List<Pair<android.accounts.Account?, PasswordVault>> =
        listOf(null to DevicePasswordVault(context)) + AccountManager.get(context).getAccountsByType("com.google").mapNotNull { account ->
            runCatching { account to PasswordRepository(context, account) }.getOrNull()
        }
}
