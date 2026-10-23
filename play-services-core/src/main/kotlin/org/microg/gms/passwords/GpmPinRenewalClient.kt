/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.content.Context
import com.google.android.gms.BuildConfig
import com.squareup.wire.GrpcClient
import okhttp3.OkHttpClient
import org.microg.gms.auth.folsom.*
import org.microg.gms.auth.folsom.utils.FolsomMemberPages
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class GpmPinRenewalAction { PREPARE, CONTINUE, REFRESH, CLOSE }
internal data class GpmPinRenewalStatus(val id: String?, val phase: GpmPinRenewalPhase?, val current: Boolean = false)

/** Explicit owner-only renewal of an existing hw_protected GPM PIN vault, using genuine retained keys. */
internal class GpmPinRenewalClient(context: Context, private val account: Account, private val accountId: String,
    private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val local = LocalKeyManager.getInstance(this.context)
    private val vault = RecoverableVaultTransport(this.context, account, accountId, ::verify)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private fun verify() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context); val guard = context.getSystemService(KeyguardManager::class.java)
        if (!guard.isDeviceSecure || guard.isDeviceLocked || guard.isKeyguardLocked || account.type != "com.google" || accountId.isBlank() ||
            !accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId ||
            local.hasPendingDomainReset(account.name, GpmPinRenewalWire.DOMAIN)) throw VaultLockedException()
    }
    fun cancel() { stopped.set(true); vault.cancel(); http.dispatcher.cancelAll() }
    private fun service(): SecurityDomainServiceClient {
        verify(); val token = context.requestOauthToken(account.name); verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun keys(): List<DomainLifecycleKey> {
        verify(); val result = ArrayList<DomainLifecycleKey>()
        try {
            local.getKeysForDomain(account.name, GpmPinRenewalWire.DOMAIN).forEach { key ->
                val raw = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                try { result.add(DomainLifecycleKey(key.keyVersion?.takeIf { it > 0 } ?: throw VaultLockedException(), raw)) }
                finally { raw.fill(0) }
            }
            verify(); return result
        } catch (failure: Throwable) { result.forEach(DomainLifecycleKey::close); throw failure }
    }
    private fun certificate(): GpmPinCertificate {
        verify(); val (xml, signature) = vault.certificates()
        return try {
            val controller = Class.forName("android.security.keystore.recovery.RecoveryController")
                .getMethod("getInstance", Context::class.java).invoke(null, context)
            verify()
            // The platform verifies the signed XML and enforces RECOVER_KEYSTORE. Raw network XML grants no authority.
            controller.javaClass.getMethod("initRecoveryService", String::class.java, ByteArray::class.java, ByteArray::class.java)
                .invoke(controller, "GoogleCloudKeyVaultServiceV1", xml, signature)
            verify()
            val root = Class.forName("android.security.keystore.recovery.TrustedRootCertificates")
                .getMethod("getRootCertificate", String::class.java).invoke(null, "GoogleCloudKeyVaultServiceV1") as? X509Certificate
                ?: throw VaultLockedException()
            GpmPinCertificates.parse(xml, root).let { paths -> paths[java.security.SecureRandom().nextInt(paths.size)] }.also { verify() }
        } finally { xml.fill(0); signature.fill(0) }
    }
    private fun status(state: GpmPinRenewalState, current: Boolean = false): GpmPinRenewalStatus {
        val attempt = state.attempt
        if (attempt != null && attempt.accountId != accountId) throw VaultLockedException()
        return GpmPinRenewalStatus(attempt?.id, attempt?.phase, current)
    }
    fun status(): GpmPinRenewalStatus {
        verify()
        return local.withGpmPinRenewal(account.name, accountId) { load, _ ->
            val raw = load(); val state = try { GpmPinRenewalState.decode(raw) } finally { raw.fill(0) }
            try { status(state).also { verify() } } finally { state.destroy() }
        }
    }
    fun perform(action: GpmPinRenewalAction, id: String? = null): GpmPinRenewalStatus {
        verify()
        return local.withGpmPinRenewal(account.name, accountId) { loadBytes, saveBytes ->
            val opened = ArrayList<GpmPinRenewalState>()
            fun load(): GpmPinRenewalState { verify(); val raw = loadBytes()
                return try { GpmPinRenewalState.decode(raw).also(opened::add) } finally { raw.fill(0) } }
            fun save(state: GpmPinRenewalState) { verify(); val raw = state.encode()
                try { saveBytes(raw) } finally { raw.fill(0) }; verify() }
            val remote = object : GpmPinRenewalRemote {
                override fun domain(id: String): ByteArray = service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
                    name = DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN), view = 2, requestId = id)).encode().also { verify() }
                override fun members(id: String): List<ByteArray> = FolsomMemberPages(accountId, { verify(); accountId }) { page ->
                    service().ListSecurityDomainMembers().executeBlocking(ListSecurityDomainMembersRequest(parent = USERS,
                        filter = listOf(DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN)), memberSelection = 3, pageToken = page,
                        filterOptions = FilterOptions(includeDeleted = false), requestId = id)).also { verify() }
                }.load().members.map { it.encode() }.also { verify() }
                override fun vaults(id: String) = vault.list(id)
                override fun update(request: ByteArray) = vault.update(request)
                override fun join(request: ByteArray): ByteArray {
                    verify(); val body = JoinSecurityDomainRequest.ADAPTER.decode(request)
                    if (!body.encode().contentEquals(request)) throw ProtocolException()
                    return service().JoinSecurityDomain().executeBlocking(body).encode()
                }
            }
            try {
                val manager = GpmPinRenewalManager(accountId, BuildConfig.VERSION_CODE, GpmPinCryptography, remote, ::keys, ::load, ::save, ::verify)
                var current = false
                when (action) {
                    GpmPinRenewalAction.PREPARE -> {
                        current = !manager.prepare(certificate())
                        if (!current) manager.continueOnce(load().attempt?.id ?: throw ProtocolException())
                    }
                    GpmPinRenewalAction.CONTINUE -> manager.continueOnce(id ?: throw ProtocolException())
                    GpmPinRenewalAction.REFRESH -> manager.reconcile()
                    GpmPinRenewalAction.CLOSE -> manager.close(id ?: throw ProtocolException())
                }
                status(load(), current).also { verify() }
            } finally { opened.forEach(GpmPinRenewalState::destroy) }
        }
    }
}
