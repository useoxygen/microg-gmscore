/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import com.google.android.gms.fido.Fido.FIDO2_KEY_CREDENTIAL_EXTRA
import com.google.android.gms.fido.fido2.api.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.microg.gms.auth.credentials.provider.PasskeyReauthUi
import org.microg.gms.auth.folsom.ui.GenericActivity
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.fido.core.*
import org.microg.gms.fido.core.protocol.*
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Private legacy GMS FIDO owner consumer. Account keys never enter the legacy transport database. */
@RequiresApi(28)
class LegacySyncedPasskeyActivity : AppCompatActivity() {
    private class Choice(val account: Account, val accountId: String, val parent: String, val tag: String?, val label: String)
    private var choices by mutableStateOf<List<Choice>>(emptyList())
    private var loading by mutableStateOf(true)
    private var selected = CompletableDeferred<Choice>()
    private var external: CompletableDeferred<Boolean>? = null
    private var externalCode = 0
    private val generation = AtomicLong()
    private lateinit var request: LegacyPasskeyRequest
    private lateinit var options: RequestOptions
    private var prf: PasskeyPrfRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT < 28 || savedInstanceState != null ||
            callingActivity?.packageName != packageName || callingActivity?.className != AuthenticatorActivity::class.java.name) {
            finish(); return
        }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.passwords_legacy_passkey_title), style = MaterialTheme.typography.headlineSmall) }
                if (loading) item { LinearProgressIndicator() }
                items(choices) { choice -> Button(onClick = { selected.complete(choice) }) {
                    Column { Text(choice.account.name); Text(choice.label) }
                } }
                if (!loading && choices.isEmpty()) item { Text(stringResource(R.string.passwords_legacy_passkey_empty)) }
                item { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) } }
            }
        } } }
        lifecycleScope.launchWhenResumed {
            try {
                request = withContext(Dispatchers.IO) { LegacyPasskeyTicketStore(this@LegacySyncedPasskeyActivity).consume(intent.data) }
                options = request.parsed()
                verifyRequest()
                prf = LegacySyncedPasskeyBackend.prf(options)
                options.checkIsValid(this@LegacySyncedPasskeyActivity, request.origin, request.caller)
                var owner = authenticate { verifyRequest() }
                choices = withContext(Dispatchers.IO) {
                    owner()
                    SyncedPasskeyVault.accounts(this@LegacySyncedPasskeyActivity).take(5).flatMap { available -> runCatching {
                        owner()
                        // Pin the account's stable Gaia ID and vault birthday before presenting a choice.
                        val parent = available.parent()
                        val vault = SyncedPasskeyVault(this@LegacySyncedPasskeyActivity, available.account, available.accountId, parent)
                        if (request.create) listOf(Choice(vault.account, vault.accountId, parent, null, options.rpId))
                        else vault.list(refresh = true).filter { item -> (prf == null || item.encryptedField != 9) && item.rpId == options.rpId &&
                            (options.signOptions.allowList.isNullOrEmpty() || options.signOptions.allowList!!.any { MessageDigest.isEqual(it.id, item.credentialId) })
                        }.take(40).map { Choice(vault.account, vault.accountId, parent, it.tag,
                            "${it.rpId}\n${it.username.ifEmpty { it.displayName }}") }
                    }.getOrElse { owner(); emptyList() } }.take(200).also { owner() }
                }
                loading = false
                val choice = selected.await()
                choices = emptyList(); loading = true
                val vault = SyncedPasskeyVault(this@LegacySyncedPasskeyActivity, choice.account, choice.accountId, choice.parent)
                val verify = { verifyRequest(); vault.check() }
                // Recovery stays owner-only, exact-account and exact-domain. It cannot reset/enroll a new domain.
                if (!withContext(Dispatchers.IO) { vault.hasDomainKeys() }) {
                    if (!external(Intent(this@LegacySyncedPasskeyActivity, GenericActivity::class.java)
                            .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, choice.account.name)
                            .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, choice.accountId)
                            .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "hw_protected")
                            .putExtra(GenericActivity.EXTRA_SESSION_ID, UUID.randomUUID().toString())
                            .putExtra(GenericActivity.EXTRA_OFFER_RESET, false), RECOVERY)) throw VaultLockedException()
                    verify()
                    if (!withContext(Dispatchers.IO) { vault.hasDomainKeys() }) throw VaultLockedException()
                }
                owner = authenticate(verify)
                val result = PasskeyReauthRetry.run(operation = { rapt -> withContext(Dispatchers.IO) {
                    owner()
                    if (request.create) create(vault, owner, rapt)
                    else null to get(vault, choice.tag ?: throw VaultLockedException(), owner, rapt)
                } }, reauthenticate = { lookup ->
                    val proof = PasskeyReauthUi.show(this@LegacySyncedPasskeyActivity, choice.account.name, choice.accountId, lookup) {
                        foreground(); verify()
                    }
                    owner = authenticate(verify)
                    proof
                })
                // Post-create PRF recovery is a separate read: its reauth must never repeat the mutation.
                val output = if (!request.create || prf == null) null else PasskeyReauthRetry.run(operation = { rapt -> withContext(Dispatchers.IO) {
                    vault.withSecret(result.first ?: throw VaultLockedException(), options.rpId, listOf(result.second.rawId), owner, rapt) { item, secret ->
                        secret.prf(prf ?: throw VaultLockedException(), item.credentialId).use(SyncedPasskeyPrf::output)
                    }
                } }, reauthenticate = { lookup ->
                    val proof = PasskeyReauthUi.show(this@LegacySyncedPasskeyActivity, choice.account.name, choice.accountId, lookup) { foreground(); verify() }
                    owner = authenticate(verify)
                    proof
                })
                val response = if (output == null) result.second else credential(result.second.rawId, result.second.response,
                    AuthenticationExtensionsClientOutputs(null, null, AuthenticationExtensionsCredPropsOutputs(true), output, null))
                withContext(Dispatchers.IO) { vault.parent() }
                owner()
                setResult(RESULT_OK, Intent().putExtra(FIDO2_KEY_CREDENTIAL_EXTRA, response.serializeToBytes()))
                finish()
            } catch (_: Exception) {
                // No request options, account names, tokens, key material or exception details enter diagnostics.
                setResult(RESULT_CANCELED); finish()
            }
        }
    }
    private fun verifyRequest() {
        LegacyPasskeyTicketStore.system(this)
        if (!MessageDigest.isEqual(request.digest, LegacyPasskeyBridgePolicy.requestDigest(request.caller, request.signer,
                request.source, request.origin, request.create, request.options)) || !LegacySyncedPasskeyBackend.supported(options)) throw VaultLockedException()
        val (signer, origin) = LegacySyncedPasskeyBackend.caller(this, options, request.caller, request.source)
        if (!MessageDigest.isEqual(signer, request.signer) || origin != request.origin) throw VaultLockedException()
    }
    private fun foreground() {
        val guard = getSystemService(KeyguardManager::class.java)
        if (isFinishing || isDestroyed || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !guard.isDeviceSecure || guard.isDeviceLocked || guard.isKeyguardLocked ||
            !getSystemService(UserManager::class.java).isUserUnlocked || !getSystemService(PowerManager::class.java).isInteractive) throw VaultLockedException()
    }
    private suspend fun authenticate(verify: () -> Unit): () -> Unit {
        verify()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) throw VaultLockedException()
        @Suppress("DEPRECATION")
        val prompt = keyguard.createConfirmDeviceCredentialIntent(options.rpId, getString(R.string.passwords_unlock_description))
            ?: throw VaultLockedException()
        if (!external(prompt, OWNER)) throw VaultLockedException()
        repeat(20) { if (!hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(50) }
        foreground(); verify()
        val grant = generation.get(); val at = SystemClock.elapsedRealtime()
        return {
            verify(); foreground()
            if (!LegacyPasskeyBridgePolicy.owner(at, SystemClock.elapsedRealtime(), grant == generation.get(),
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), hasWindowFocus(),
                    keyguard.isDeviceSecure, !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked,
                    getSystemService(PowerManager::class.java).isInteractive)) throw VaultLockedException()
        }
    }
    private suspend fun external(intent: Intent, code: Int): Boolean {
        if (external != null) throw VaultLockedException()
        val reply = CompletableDeferred<Boolean>(); external = reply; externalCode = code
        return try { startActivityForResult(intent, code); withTimeout(120_000) { reply.await() } }
        finally { external = null; externalCode = 0 }
    }
    @Deprecated("Legacy owner confirmation ABI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == externalCode) external?.complete(resultCode == RESULT_OK)
    }
    private fun get(vault: SyncedPasskeyVault, tag: String, owner: () -> Unit, rapt: String?): PublicKeyCredential =
        vault.withSecret(tag, options.rpId, options.signOptions.allowList.orEmpty().map { it.id }, owner, rapt) { item, secret ->
            val key = secret.privateKey()
            try {
                val (clientData, hash) = getClientDataAndHash(this, options, request.caller)
                if (hash.size != 32) throw VaultLockedException()
                val auth = authenticator(options.rpId)
                owner()
                val signed = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(auth.encode() + hash); sign() }
                val output = prf?.let { secret.prf(it, item.credentialId).use(SyncedPasskeyPrf::output) }
                owner()
                credential(item.credentialId, AuthenticatorAssertionResponse(item.credentialId, clientData, auth.encode(), signed, item.userId),
                    output?.let { AuthenticationExtensionsClientOutputs(null, null, null, it, null) })
            } finally { runCatching { key.destroy() } }
        }
    private fun create(vault: SyncedPasskeyVault, owner: () -> Unit, rapt: String?): Pair<String, PublicKeyCredential> {
        val base = options.registerOptions
        val existing = vault.list(refresh = true)
        if (PasskeyExclusions.contains(base.rp.id, base.excludeList.orEmpty().map { it.id }, existing)) throw VaultLockedException()
        owner()
        val key = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val raw = key.private.encoded ?: throw VaultLockedException()
        try {
            val item = vault.createSpecifics(base.rp.id, base.user.id, base.user.name, base.user.displayName, raw, owner, rapt)
            val (clientData, hash) = getClientDataAndHash(this, options, request.caller)
            if (hash.size != 32) throw VaultLockedException()
            val pub = key.public as ECPublicKey
            val cose = CoseKey(EC2Algorithm.ES256, pub.w.affineX, pub.w.affineY, 1, 32)
            val auth = authenticator(base.rp.id, AttestedCredentialData(ByteArray(16), item.credentialId, cose.encode()))
            vault.create(item, owner, base.excludeList.orEmpty().map { it.id })
            owner()
            return item.tag to credential(item.credentialId, AuthenticatorAttestationResponse(item.credentialId, clientData,
                NoneAttestationObject(auth).encode(), arrayOf("internal")))
        } finally { raw.fill(0); runCatching { key.private.destroy() } }
    }
    private fun authenticator(rp: String, attested: AttestedCredentialData? = null) = AuthenticatorData(
        MessageDigest.getInstance("SHA-256").digest(rp.toByteArray(Charsets.UTF_8)), true, true, 0, attested).apply {
        backupEligible = true; backedUp = true
    }
    private fun credential(id: ByteArray, response: AuthenticatorResponse, extensions: AuthenticationExtensionsClientOutputs? = null) =
        PublicKeyCredential.Builder().setId(id.encodeBase64Url()).setRawId(id).setResponse(response).setAuthenticatorAttachment("platform")
            .setAuthenticationExtensionsClientOutputs(extensions).build()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (rejectsPasswordTouch(event.flags)) false else super.dispatchTouchEvent(event)
    override fun onPause() { generation.incrementAndGet(); super.onPause() }
    override fun onStop() { if (external == null) finish(); super.onStop() }
    override fun onDestroy() { generation.incrementAndGet(); selected.cancel(); external?.cancel(); choices = emptyList(); super.onDestroy() }
    companion object { private const val OWNER = 6881; private const val RECOVERY = 6882 }
}
