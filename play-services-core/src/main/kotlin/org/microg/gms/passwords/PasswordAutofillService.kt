/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.os.CancellationSignal
import android.service.autofill.*
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.google.android.gms.R
import kotlinx.coroutines.*

@RequiresApi(28)
class PasswordAutofillService : AutofillService() {
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDestroy() { requests.cancel(); super.onDestroy() }
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (android.os.Build.VERSION.SDK_INT < 28) { callback.onSuccess(null); return }
        val job = requests.launch {
            try {
                val structure = request.fillContexts.lastOrNull()?.structure ?: throw ProtocolException()
                val target = PasswordAutofillTarget.parse(this@PasswordAutofillService, structure)
                val response = FillResponse.Builder()
                val tickets = PasswordRequestTickets(this@PasswordAutofillService)
                var canSave = false
                val vaults: List<Pair<android.accounts.Account?, PasswordVault>> = listOf(null to DevicePasswordVault(this@PasswordAutofillService)) +
                    AccountManager.get(this@PasswordAutofillService).getAccountsByType("com.google").mapNotNull { account ->
                        runCatching { account to PasswordRepository(this@PasswordAutofillService, account) }.getOrNull()
                    }
                vaults.forEach { (account, repo) ->
                    try {
                        if (repo.enabled) {
                            val entries = repo.list()
                            if (entries.none { it.data.blocked && it.data.realm == target.realm }) canSave = true
                            entries.filter { it.data.canFillRealm(target.realm) }.take(100).forEach { entry ->
                                val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                    setTextViewText(android.R.id.text1, getString(R.string.passwords_fill))
                                }
                                val auth = tickets.issueAutofill(account, entry.id, target.realm, target.caller, false, target.payload(false))
                                @Suppress("DEPRECATION")
                                val dataset = Dataset.Builder(presentation).setValue(target.passwordId, null).apply {
                                    target.usernameId?.let { setValue(it, null) }
                                    setAuthentication(auth.intentSender)
                                }.build()
                                response.addDataset(dataset)
                            }
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { /* A locked account must not suppress another available vault. */ }
                }
                val ids = listOfNotNull(target.usernameId, target.passwordId).toTypedArray()
                if (canSave) response.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME, ids).build())
                if (!cancellationSignal.isCanceled) callback.onSuccess(response.build())
            } catch (_: Exception) { if (!cancellationSignal.isCanceled) callback.onSuccess(null) }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        if (android.os.Build.VERSION.SDK_INT < 28) { callback.onFailure(getString(R.string.passwords_sync_failed)); return }
        requests.launch {
            try {
                val target = PasswordAutofillTarget.parse(this@PasswordAutofillService, request.fillContexts.last().structure)
                if (target.password.isEmpty() || target.password.length > 100_000 || target.username.length > 100_000) throw ProtocolException()
                val intent = PasswordRequestTickets(this@PasswordAutofillService).issueAutofill(null, null,
                    target.realm, target.caller, true, target.payload(true))
                callback.onSuccess(intent.intentSender)
            } catch (_: Exception) { callback.onFailure(getString(R.string.passwords_sync_failed)) }
        }
    }
}
