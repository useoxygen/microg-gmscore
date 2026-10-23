/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials.provider

import android.accounts.Account
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.fido.fido2.api.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.microg.gms.fido.core.*
import org.microg.gms.fido.core.protocol.*
import org.microg.gms.passwords.*
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** A real Credential Manager consumer. Keys and signatures remain in the native provider/app result boundary. */
@RequiresApi(34)
internal object SyncedPasskeyProvider {
    fun entries(context: Context, option: BeginGetPublicKeyCredentialOption, request: BeginGetCredentialRequest): List<CredentialEntry> {
        if (!supportsGet(option.requestJson)) return emptyList()
        val caller = PasswordCaller.from(request.callingAppInfo ?: return emptyList())
        val options = JSONObject(option.requestJson).parsePublicKeyCredentialRequestOptions()
        SyncedPasskey.checkedRp(options.rpId)
        val tickets = PasskeyRequestTickets(context)
        return SyncedPasskeyVault.accounts(context).take(5).flatMap { vault -> runCatching {
            val values = PasskeySnapshots.candidates({ vault.list() }, { vault.list(refresh = true) })
            values.filter { it.rpId == options.rpId && (options.allowList.isNullOrEmpty() ||
                options.allowList!!.any { allowed -> MessageDigest.isEqual(allowed.id, it.credentialId) }) }.take(40).map { item ->
                PublicKeyCredentialEntry(context = context, username = item.username.ifEmpty { item.displayName.ifEmpty { vault.account.name } },
                    displayName = item.displayName.ifEmpty { null },
                    pendingIntent = tickets.issue(vault, item.tag, item.rpId, caller.identity, option.requestJson, false),
                    beginGetPublicKeyCredentialOption = option, isAutoSelectAllowed = false)
            }
        }.getOrDefault(emptyList()) }
    }
    fun createEntries(context: Context, request: BeginCreatePublicKeyCredentialRequest): List<CreateEntry> {
        if (!supportsCreate(request.requestJson)) return emptyList()
        val caller = PasswordCaller.from(request.callingAppInfo ?: return emptyList())
        val options = JSONObject(request.requestJson).parsePublicKeyCredentialCreationOptions()
        SyncedPasskey.checkedRp(options.rp.id)
        return SyncedPasskeyVault.accounts(context).mapNotNull { vault -> runCatching {
            CreateEntry(vault.account.name, PasskeyRequestTickets(context).issue(vault, null, options.rp.id,
                caller.identity, request.requestJson, true))
        }.getOrNull() }
    }
    fun get(activity: PublicKeyProxyActivity, request: ProviderGetCredentialRequest): Boolean {
        if (activity.intent.data?.scheme != "microg-passkey") return false
        activity.lifecycleScope.launchWhenResumed {
            try {
                val option = request.credentialOptions.singleOrNull() as? GetPublicKeyCredentialOption ?: throw SecurityException()
                val caller = verifyCaller(activity, request.callingAppInfo)
                val ticket = withContext(Dispatchers.IO) { PasskeyRequestTickets(activity).consume(activity.intent.data, caller.identity, option.requestJson, false) }
                val vault = selected(activity, ticket)
                val base = JSONObject(option.requestJson).parsePublicKeyCredentialRequestOptions()
                if (ticket.string(4) != base.rpId) throw SecurityException()
                if (!supportsGet(option.requestJson)) throw SecurityException()
                val options = options(activity, base, caller, option.clientDataHash)
                options.checkIsValid(activity, origin(activity, caller, request.callingAppInfo.packageName), request.callingAppInfo.packageName)
                recover(activity, vault)
                val owner = authenticate(activity, base.rpId) { verifyCaller(activity, request.callingAppInfo); vault.check() }
                val result = withContext(Dispatchers.IO) {
                    vault.withSecret(ticket.string(3), base.rpId, base.allowList.orEmpty().map { it.id }, owner) { item, secret ->
                        val key = secret.privateKey()
                        try {
                            val (clientData, hash) = getClientDataAndHash(activity, options, request.callingAppInfo.packageName)
                            if (hash.size != 32) throw SecurityException()
                            val auth = authenticator(base.rpId)
                            owner()
                            val signed = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(auth.encode() + hash); sign() }
                            owner()
                            credential(item.credentialId, AuthenticatorAssertionResponse(item.credentialId, clientData, auth.encode(), signed, item.userId)).toJson()
                        } finally { runCatching { key.destroy() } }
                    }
                }
                owner(); activity.finishWithSuccess(GetCredentialResponse(androidx.credentials.PublicKeyCredential(result)))
            } catch (_: Exception) { activity.finishWithException() }
        }
        return true
    }
    fun create(activity: PublicKeyProxyActivity, request: ProviderCreateCredentialRequest): Boolean {
        if (activity.intent.data?.scheme != "microg-passkey") return false
        activity.lifecycleScope.launchWhenResumed {
            try {
                val option = request.callingRequest as? CreatePublicKeyCredentialRequest ?: throw SecurityException()
                val caller = verifyCaller(activity, request.callingAppInfo)
                val ticket = withContext(Dispatchers.IO) { PasskeyRequestTickets(activity).consume(activity.intent.data, caller.identity, option.requestJson, true) }
                val vault = selected(activity, ticket)
                val base = JSONObject(option.requestJson).parsePublicKeyCredentialCreationOptions()
                if (ticket.string(4) != base.rp.id || !supportsCreate(option.requestJson)) throw SecurityException()
                val browser = caller.realms.single().startsWith("https://")
                val options: RequestOptions = if (browser) BrowserPublicKeyCredentialCreationOptions.Builder()
                    .setPublicKeyCredentialCreationOptions(base).setOrigin(android.net.Uri.parse(origin(activity, caller, request.callingAppInfo.packageName)))
                    .apply { option.clientDataHash?.let(::setClientDataHash) }.build() else base
                options.checkIsValid(activity, origin(activity, caller, request.callingAppInfo.packageName), request.callingAppInfo.packageName)
                recover(activity, vault)
                val owner = authenticate(activity, base.rp.id) { verifyCaller(activity, request.callingAppInfo); vault.check() }
                if (withContext(Dispatchers.IO) { vault.hasPendingCreate() }) {
                    if (!activity.confirmAbandonSyncedPasskeyAttempt()) throw VaultLockedException()
                    repeat(10) { if (!activity.hasWindowFocus()) delay(50) }
                    withContext(Dispatchers.IO) { vault.discardPendingCreate(owner) }
                }
                val result = withContext(Dispatchers.IO) {
                    val existing = vault.list(refresh = true)
                    if (base.excludeList.orEmpty().any { excluded -> existing.any { MessageDigest.isEqual(excluded.id, it.credentialId) } }) throw SecurityException()
                    owner()
                    val key = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
                    val pkcs8 = key.private.encoded ?: throw SecurityException()
                    try {
                        val specifics = vault.createSpecifics(base.rp.id, base.user.id, base.user.name, base.user.displayName, pkcs8, owner)
                        val (clientData, hash) = getClientDataAndHash(activity, options, request.callingAppInfo.packageName)
                        if (hash.size != 32) throw SecurityException()
                        val pub = key.public as ECPublicKey
                        val cose = CoseKey(EC2Algorithm.ES256, pub.w.affineX, pub.w.affineY, 1, 32)
                        val auth = authenticator(base.rp.id, AttestedCredentialData(ByteArray(16), specifics.credentialId, cose.encode()))
                        // Only a successful remote create and durable local save can claim a synced registration.
                        vault.create(specifics, owner, base.excludeList.orEmpty().map { it.id })
                        val response = credential(specifics.credentialId, AuthenticatorAttestationResponse(specifics.credentialId, clientData,
                            NoneAttestationObject(auth).encode(), arrayOf("internal"))).toJson()
                        if (JSONObject(option.requestJson).optJSONObject("extensions")?.optBoolean("credProps") == true) {
                            val json = JSONObject(response)
                            json.getJSONObject("clientExtensionResults").put("credProps", JSONObject().put("rk", true))
                            json.toString()
                        } else response
                    } finally { pkcs8.fill(0); runCatching { key.private.destroy() } }
                }
                owner(); activity.finishWithSuccess(CreatePublicKeyCredentialResponse(result))
            } catch (_: Exception) { activity.finishWithException() }
        }
        return true
    }
    private fun selected(context: Context, ticket: Proto) = SyncedPasskeyVault(context,
        Account(ticket.string(1), "com.google"), ticket.string(2), ticket.string(10))
    private suspend fun recover(activity: PublicKeyProxyActivity, vault: SyncedPasskeyVault) {
        if (withContext(Dispatchers.IO) { vault.hasDomainKeys() }) return
        if (!activity.recoverSyncedPasskeys(vault.account, vault.accountId)) throw VaultLockedException()
        vault.check()
        if (!withContext(Dispatchers.IO) { vault.hasDomainKeys() }) throw VaultLockedException()
    }
    private suspend fun authenticate(activity: PublicKeyProxyActivity, rp: String, verify: () -> Unit): () -> Unit {
        if (!activity.confirmSyncedPasskeyOwner(rp)) throw VaultLockedException()
        repeat(10) { if (!activity.hasWindowFocus()) delay(50) }
        val at = SystemClock.elapsedRealtime()
        return {
            verify()
            val lock = activity.getSystemService(KeyguardManager::class.java)
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus() ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || SystemClock.elapsedRealtime() - at !in 0..60_000 ||
                lock.isDeviceLocked || lock.isKeyguardLocked || !activity.getSystemService(UserManager::class.java).isUserUnlocked ||
                !activity.getSystemService(PowerManager::class.java).isInteractive) throw VaultLockedException()
        }
    }
    private fun verifyCaller(context: Context, info: CallingAppInfo): PasswordCaller {
        val caller = PasswordCaller.from(info)
        val actual = context.packageManager.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: throw SecurityException()
        val claimed = info.signingInfo.apkContentsSigners?.singleOrNull()?.toByteArray() ?: throw SecurityException()
        if (!MessageDigest.isEqual(actual, claimed)) throw SecurityException()
        return caller
    }
    private fun origin(context: Context, caller: PasswordCaller, pkg: String): String = caller.realms.single().let {
        if (it.startsWith("https://")) it.removeSuffix("/") else getApkKeyHashOrigin(context, pkg)
    }
    private fun options(context: Context, base: PublicKeyCredentialRequestOptions, caller: PasswordCaller, hash: ByteArray?): RequestOptions =
        if (caller.realms.single().startsWith("https://")) BrowserPublicKeyCredentialRequestOptions.Builder()
            .setPublicKeyCredentialRequestOptions(base).setOrigin(android.net.Uri.parse(caller.realms.single().removeSuffix("/")))
            .apply { hash?.let(::setClientDataHash) }.build() else base
    private fun authenticator(rp: String, attested: AttestedCredentialData? = null) = AuthenticatorData(
        MessageDigest.getInstance("SHA-256").digest(rp.toByteArray(Charsets.UTF_8)), true, true, 0, attested).apply {
        backupEligible = true; backedUp = true
    }
    private fun credential(id: ByteArray, response: AuthenticatorResponse) = PublicKeyCredential.Builder().setId(id.encodeBase64Url())
        .setRawId(id).setResponse(response).setAuthenticatorAttachment("platform").build()
    private fun supportsCreate(request: String): Boolean = runCatching {
        val json = JSONObject(request)
        val params = json.getJSONArray("pubKeyCredParams")
        val algorithms = (0 until params.length()).mapNotNull { index ->
            params.getJSONObject(index).let { if (it.optString("type") == "public-key") it.getInt("alg") else null }
        }
        val selection = if (json.has("authenticatorSelection")) json.getJSONObject("authenticatorSelection") else null
        val extensions = extensionNames(json) ?: return@runCatching false
        PasskeyRequestSupport.create(algorithms, if (json.has("attestation")) json.getString("attestation") else null,
            selection?.let { if (it.has("authenticatorAttachment")) it.getString("authenticatorAttachment") else null }, extensions)
    }.getOrDefault(false)
    private fun supportsGet(request: String): Boolean = runCatching {
        PasskeyRequestSupport.get(extensionNames(JSONObject(request)) ?: return@runCatching false)
    }.getOrDefault(false)
    private fun extensionNames(json: JSONObject): Set<String>? {
        if (!json.has("extensions")) return emptySet()
        val extensions = json.optJSONObject("extensions") ?: return null
        if (extensions.has("credProps") && extensions.opt("credProps") !is Boolean) return null
        val names = HashSet<String>()
        val iterator = extensions.keys()
        while (iterator.hasNext()) names.add(iterator.next())
        return names
    }
}
