/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Intent
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.*
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(34)
class PasswordProxyActivity : PasswordOwnerActivity() {
    private var get: ProviderGetCredentialRequest? = null
    private var create: ProviderCreateCredentialRequest? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            get = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            create = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            if ((get == null) == (create == null)) throw SecurityException()
            authenticateOwner()
        } catch (_: Exception) { fail() }
    }
    override fun onOwnerAuthenticated() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val caller = PasswordCaller.from((get?.callingAppInfo ?: create?.callingAppInfo) ?: throw SecurityException())
                    val ticket = PasswordRequestTickets(this@PasswordProxyActivity).consume(intent.data, caller.identity, create != null)
                    if (ticket.realm !in caller.realms) throw SecurityException()
                    val repo: PasswordVault = ticket.account?.let { PasswordRepository(this@PasswordProxyActivity, it) }
                        ?: DevicePasswordVault(this@PasswordProxyActivity)
                    val creating = create
                    if (creating != null) {
                        val request = creating.callingRequest as? CreatePasswordRequest ?: throw SecurityException()
                        // Preserve fields and identity of an existing exact facet/username record.
                        val existing = repo.list().singleOrNull { it.data.matchesRealmAuthority(ticket.realm) && it.data.username == request.id }
                        val data = existing?.data?.withPassword(request.password, System.currentTimeMillis()) ?: PasswordData.create(
                            ticket.realm, ticket.realm, request.id, request.password, System.currentTimeMillis())
                        repo.upsert(Proto.field(1, data.encode()))
                        Intent().also { PendingIntentHandler.setCreateCredentialResponse(it, CreatePasswordResponse()) }
                    } else {
                        val matcher = PasswordAffiliationMatcher(this@PasswordProxyActivity, repo)
                        val entry = repo.list().singleOrNull { it.id == ticket.tag && matcher.canFill(it.data, ticket.realm) }
                            ?: throw VaultLockedException()
                        if (get!!.credentialOptions.filterIsInstance<GetPasswordOption>().none {
                            it.allowedUserIds.isEmpty() || entry.data.username in it.allowedUserIds
                        }) throw SecurityException()
                        Intent().also { PendingIntentHandler.setGetCredentialResponse(it,
                            GetCredentialResponse(PasswordCredential(entry.data.username, entry.data.password))) }
                    }
                }
                requireOwner()
                setResult(RESULT_OK, result); finish()
            } catch (_: Exception) { fail() }
        }
    }
    override fun onOwnerAuthenticationFailed() = fail()
    override fun onOwnerLocked() { /* The foreground grant is rechecked after asynchronous vault work. */ }
    private fun fail() {
        val result = Intent()
        if (create != null) PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException())
        else PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException())
        setResult(RESULT_OK, result); finish()
    }
}
