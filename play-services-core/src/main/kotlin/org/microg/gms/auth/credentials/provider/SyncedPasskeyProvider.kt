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
        if (!SyncedPasskeyRequestSupport.get(option.requestJson)) return emptyList()
        val caller = PasswordCaller.from(request.callingAppInfo ?: return emptyList())
        val options = JSONObject(option.requestJson).parsePublicKeyCredentialRequestOptions()
        val prf = SyncedPasskeyRequestSupport.prf(option.requestJson, false)
        SyncedPasskey.checkedRp(options.rpId)
        val tickets = PasskeyRequestTickets(context)
        return SyncedPasskeyVault.accounts(context).take(5).flatMap { vault -> runCatching {
            val values = PasskeySnapshots.candidates({ vault.list() }, { vault.list(refresh = true) })
            values.filter { (prf == null || it.encryptedField != 9) && it.rpId == options.rpId && (options.allowList.isNullOrEmpty() ||
                options.allowList!!.any { allowed -> MessageDigest.isEqual(allowed.id, it.credentialId) }) }.take(40).map { item ->
                PublicKeyCredentialEntry(context = context, username = item.username.ifEmpty { item.displayName.ifEmpty { vault.account.name } },
                    displayName = item.displayName.ifEmpty { null },
                    pendingIntent = tickets.issue(vault, item.tag, item.rpId, caller.identity, option.requestJson, false),
                    beginGetPublicKeyCredentialOption = option, isAutoSelectAllowed = false)
            }
        }.getOrDefault(emptyList()) }
    }
    fun createEntries(context: Context, request: BeginCreatePublicKeyCredentialRequest): List<CreateEntry> {
        if (!SyncedPasskeyRequestSupport.create(request.requestJson)) return emptyList()
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
                if (!SyncedPasskeyRequestSupport.get(option.requestJson)) throw SecurityException()
                val prf = SyncedPasskeyRequestSupport.prf(option.requestJson, false)
                val blobs = SyncedPasskeyBlobReads.request(option.requestJson)
                val options = options(activity, base, caller, option.clientDataHash)
                options.checkIsValid(activity, origin(activity, caller, request.callingAppInfo.packageName), request.callingAppInfo.packageName)
                recover(activity, vault)
                val verify = { verifyCaller(activity, request.callingAppInfo); vault.check() }
                var owner = authenticate(activity, base.rpId, verify)
                val result = PasskeyReauthRetry.run(operation = { rapt -> withContext(Dispatchers.IO) {
                    vault.withSecret(ticket.string(3), base.rpId, base.allowList.orEmpty().map { it.id }, owner, rapt) { item, secret ->
                        SyncedPasskeyBlobReads.read(secret, blobs).use { blobResult ->
                            val key = secret.privateKey()
                            try {
                                val (clientData, hash) = getClientDataAndHash(activity, options, request.callingAppInfo.packageName)
                                if (hash.size != 32) throw SecurityException()
                                val auth = authenticator(base.rpId, extensions = blobResult?.credBlob?.let(::encodeCredBlobAuthenticationExtension))
                                owner()
                                val signed = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(auth.encode() + hash); sign() }
                                owner()
                                val output = prf?.let { secret.prf(it, item.credentialId).use(SyncedPasskeyPrf::output) }
                                owner()
                                val json = credential(item.credentialId, AuthenticatorAssertionResponse(item.credentialId, clientData, auth.encode(), signed, item.userId),
                                    output?.let { AuthenticationExtensionsClientOutputs(null, null, null, it, null) }).toJson()
                                SyncedPasskeyBlobReads.output(json, blobs, blobResult).also { owner() }
                            } finally { runCatching { key.destroy() } }
                        }
                    }
                } }, reauthenticate = { lookup ->
                    val proof = PasskeyReauthUi.show(activity, vault.account.name, vault.accountId, lookup) { requireForeground(activity, verify) }
                    owner = authenticate(activity, base.rpId, verify)
                    proof
                })
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
                if (ticket.string(4) != base.rp.id || !SyncedPasskeyRequestSupport.create(option.requestJson)) throw SecurityException()
                val prf = SyncedPasskeyRequestSupport.prf(option.requestJson, true)
                val browser = caller.realms.single().startsWith("https://")
                val options: RequestOptions = if (browser) BrowserPublicKeyCredentialCreationOptions.Builder()
                    .setPublicKeyCredentialCreationOptions(base).setOrigin(android.net.Uri.parse(origin(activity, caller, request.callingAppInfo.packageName)))
                    .apply { option.clientDataHash?.let(::setClientDataHash) }.build() else base
                options.checkIsValid(activity, origin(activity, caller, request.callingAppInfo.packageName), request.callingAppInfo.packageName)
                recover(activity, vault)
                val verify = { verifyCaller(activity, request.callingAppInfo); vault.check() }
                var owner = authenticate(activity, base.rp.id, verify)
                if (withContext(Dispatchers.IO) { vault.hasPendingCreate() }) {
                    if (!activity.confirmAbandonSyncedPasskeyAttempt()) throw VaultLockedException()
                    repeat(10) { if (!activity.hasWindowFocus()) delay(50) }
                    withContext(Dispatchers.IO) { vault.discardPendingCreate(owner) }
                }
                val result = PasskeyReauthRetry.run(operation = { rapt -> withContext(Dispatchers.IO) {
                    val existing = vault.list(refresh = true)
                    if (PasskeyExclusions.contains(base.rp.id, base.excludeList.orEmpty().map { it.id }, existing)) throw SecurityException()
                    owner()
                    val key = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
                    val pkcs8 = key.private.encoded ?: throw SecurityException()
                    try {
                        val specifics = vault.createSpecifics(base.rp.id, base.user.id, base.user.name, base.user.displayName, pkcs8, owner, rapt)
                        val (clientData, hash) = getClientDataAndHash(activity, options, request.callingAppInfo.packageName)
                        if (hash.size != 32) throw SecurityException()
                        val pub = key.public as ECPublicKey
                        val cose = CoseKey(EC2Algorithm.ES256, pub.w.affineX, pub.w.affineY, 1, 32)
                        val auth = authenticator(base.rp.id, AttestedCredentialData(ByteArray(16), specifics.credentialId, cose.encode()))
                        // Only a successful remote create and durable local save can claim a synced registration.
                        vault.create(specifics, owner, base.excludeList.orEmpty().map { it.id })
                        specifics to AuthenticatorAttestationResponse(specifics.credentialId, clientData,
                            NoneAttestationObject(auth).encode(), arrayOf("internal"))
                    } finally { pkcs8.fill(0); runCatching { key.private.destroy() } }
                } }, reauthenticate = { lookup ->
                    val proof = PasskeyReauthUi.show(activity, vault.account.name, vault.accountId, lookup) { requireForeground(activity, verify) }
                    owner = authenticate(activity, base.rp.id, verify)
                    proof
                })
                // A challenge after successful creation may retry only this read, never the create mutation.
                val output = if (prf == null) null else PasskeyReauthRetry.run(operation = { rapt -> withContext(Dispatchers.IO) {
                    vault.withSecret(result.first.tag, base.rp.id, listOf(result.first.credentialId), owner, rapt) { item, secret ->
                        secret.prf(prf, item.credentialId).use(SyncedPasskeyPrf::output)
                    }
                } }, reauthenticate = { lookup ->
                    val proof = PasskeyReauthUi.show(activity, vault.account.name, vault.accountId, lookup) { requireForeground(activity, verify) }
                    owner = authenticate(activity, base.rp.id, verify)
                    proof
                })
                val extensions = if (output == null && !SyncedPasskeyRequestSupport.credProps(option.requestJson)) null else
                    AuthenticationExtensionsClientOutputs(null, null,
                        if (SyncedPasskeyRequestSupport.credProps(option.requestJson)) AuthenticationExtensionsCredPropsOutputs(true) else null, output, null)
                owner(); activity.finishWithSuccess(CreatePublicKeyCredentialResponse(credential(result.first.credentialId, result.second, extensions).toJson()))
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
            requireForeground(activity, verify)
            if (SystemClock.elapsedRealtime() - at !in 0..60_000) throw VaultLockedException()
        }
    }
    private fun requireForeground(activity: PublicKeyProxyActivity, verify: () -> Unit) {
        verify()
        val lock = activity.getSystemService(KeyguardManager::class.java)
        if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || lock.isDeviceLocked || lock.isKeyguardLocked ||
            !activity.getSystemService(UserManager::class.java).isUserUnlocked ||
            !activity.getSystemService(PowerManager::class.java).isInteractive) throw VaultLockedException()
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
    private fun authenticator(rp: String, attested: AttestedCredentialData? = null, extensions: ByteArray? = null) = AuthenticatorData(
        MessageDigest.getInstance("SHA-256").digest(rp.toByteArray(Charsets.UTF_8)), true, true, 0, attested, extensions).apply {
        backupEligible = true; backedUp = true
    }
    private fun credential(id: ByteArray, response: AuthenticatorResponse, extensions: AuthenticationExtensionsClientOutputs? = null) =
        PublicKeyCredential.Builder().setId(id.encodeBase64Url()).setRawId(id).setResponse(response)
            .setAuthenticatorAttachment("platform").setAuthenticationExtensionsClientOutputs(extensions).build()
}

/** Read the same two representations accepted by the options parser, before any unsupported field is dropped. */
internal object SyncedPasskeyRequestSupport {
    fun create(request: String): Boolean = runCatching {
        val json = JSONObject(request)
        val params = json.getJSONArray("pubKeyCredParams")
        val algorithms = (0 until params.length()).mapNotNull { index ->
            params.getJSONObject(index).let { if (it.optString("type") == "public-key") it.getInt("alg") else null }
        }
        val selection = if (json.has("authenticatorSelection")) json.getJSONObject("authenticatorSelection") else null
        val extensions = extensionNames(json) ?: return@runCatching false
        SyncedPasskeyPrf.json(extensionObject(json) ?: return@runCatching false, true)
        PasskeyRequestSupport.create(algorithms, if (json.has("attestation")) json.getString("attestation") else null,
            selection?.let { if (it.has("authenticatorAttachment")) it.getString("authenticatorAttachment") else null }, extensions)
    }.getOrDefault(false)
    fun get(request: String): Boolean = runCatching {
        val json = JSONObject(request)
        val names = extensionNames(json) ?: return@runCatching false
        SyncedPasskeyPrf.json(extensionObject(json) ?: return@runCatching false, false)
        SyncedPasskeyBlobReads.request(request)
        PasskeyRequestSupport.get(names - setOf("getCredBlob", "largeBlob"))
    }.getOrDefault(false)
    fun prf(request: String, create: Boolean): PasskeyPrfRequest? =
        SyncedPasskeyPrf.json(extensionObject(JSONObject(request)) ?: throw ProtocolException(), create)
    fun credProps(request: String): Boolean = runCatching {
        extensionObject(JSONObject(request))?.opt("credProps") == true
    }.getOrDefault(false)
    private fun extensionObject(json: JSONObject): JSONObject? {
        val fields = listOf("extensions", "authenticationExtensions").filter { json.has(it) }
        if (fields.isEmpty()) return JSONObject()
        // The parser gives the alias priority. A second representation must not hide another request.
        if (fields.size != 1) return null
        return json.optJSONObject(fields.single())
    }
    private fun extensionNames(json: JSONObject): Set<String>? {
        val extensions = extensionObject(json) ?: return null
        if (extensions.has("credProps") && extensions.opt("credProps") !is Boolean) return null
        val names = HashSet<String>()
        val iterator = extensions.keys()
        while (iterator.hasNext()) names.add(iterator.next())
        return names
    }
}
