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
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RecoverableVaultAction { PREPARE, CONTINUE, REFRESH, CLOSE }
internal data class RecoverableVaultStatus(val domain: String, val phase: RecoverableVaultPhase?, val attemptId: String?,
    val waitingForSnapshot: Boolean) { val complete: Boolean get() = phase == RecoverableVaultPhase.CONFIRMED }

/** Explicit owner backup of a genuine recovered domain to Android's lock-screen recoverable keystore. */
internal class RecoverableVaultClient(context: Context, private val account: Account, private val accountId: String,
    private val domain: String, private val expectedParent: String?, private val authorize: () -> Unit, private val sdkMode: Boolean = false) {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val local = LocalKeyManager.getInstance(this.context)
    private val repository = PasswordRepository(this.context, account, expectedParent)
    private val parent = expectedParent ?: if (sdkMode) existingPasswordParent() else
        if (domain == "chromesync") repository.trustedVaultRecoveryParent(accountId) else null
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val vault = RecoverableVaultTransport(this.context, account, accountId, ::verify)
    init { DomainLifecycleWire.checkedDomain(domain); verify() }
    private fun existingPasswordParent(): String? {
        if (!repository.enabled) return null
        val type5 = repository.store.locked { session ->
            val state = session.load(); val metadata = state.pendingMetadata ?: state.metadata
            metadata != null && Proto.decode(Proto.decode(metadata).one(3)).integer(2) == 5L
        }
        return if (type5) repository.trustedVaultRecoveryParent(accountId) else null
    }
    fun cancel() { stopped.set(true); vault.cancel(); http.dispatcher.cancelAll() }
    private fun verify() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context)
        val lock = context.getSystemService(KeyguardManager::class.java)
        if (!lock.isDeviceSecure || lock.isDeviceLocked || lock.isKeyguardLocked || account.type != "com.google" ||
            !accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId ||
            local.hasPendingDomainReset(account.name, "chromesync") || local.hasPendingDomainReset(account.name, "hw_protected")) throw VaultLockedException()
        if (parent != null && repository.trustedVaultRecoveryParent(accountId) != parent) throw VaultResetException()
    }
    private fun service(): SecurityDomainServiceClient {
        verify(); val token = context.requestOauthToken(account.name); verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun material(): List<DomainLifecycleKey> {
        verify()
        val result = ArrayList<DomainLifecycleKey>()
        try {
            if (domain == "chromesync" && !sdkMode) {
                val recoveryParent = parent ?: throw VaultLockedException()
                val retained = repository.versionedTrustedVaultKeys(recoveryParent, accountId)
                val raw = retained.map { it.material }
                try {
                    val metadata = PasswordApi(repository.sharingTransport()).metadata()
                    verify()
                    if (Proto.decode(metadata).string(1) != recoveryParent) throw VaultResetException()
                    VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
                    retained.forEachIndexed { index, key -> result.add(DomainLifecycleKey(key.epoch, raw[index])) }
                } finally { raw.forEach { it.fill(0) }; retained.forEach { it.destroy() } }
            } else local.getKeysForDomain(account.name, domain).forEach { key ->
                val raw = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                try { result.add(DomainLifecycleKey(key.keyVersion?.takeIf { it > 0 } ?: throw VaultLockedException(), raw)) }
                finally { raw.fill(0) }
            }
            if (sdkMode && domain == "chromesync" && parent != null) {
                val raw = result.map { it.material }
                try {
                    val metadata = PasswordApi(repository.sharingTransport()).metadata()
                    verify()
                    if (Proto.decode(metadata).string(1) != parent) throw VaultResetException()
                    VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
                } finally { raw.forEach { it.fill(0) } }
            }
            verify(); return result
        } catch (failure: Throwable) { result.forEach(DomainLifecycleKey::close); throw failure }
    }
    private fun status(state: RecoverableVaultState, allowOtherDomain: Boolean = false): RecoverableVaultStatus {
        val attempt = state.attempt
        if (attempt != null && (attempt.accountId != accountId || !allowOtherDomain && attempt.domain != domain)) throw VaultLockedException()
        val phase = if (attempt != null && attempt.parent != parent) RecoverableVaultPhase.REVOKED else attempt?.phase
        return RecoverableVaultStatus(attempt?.domain ?: domain, phase, attempt?.id, phase == RecoverableVaultPhase.WAITING_FOR_SNAPSHOT)
    }
    fun status(): RecoverableVaultStatus {
        verify()
        return local.withRecoverableVault(account.name, accountId) { load, _ ->
            val state = RecoverableVaultState.decode(load())
            try { status(state).also { verify() } } finally { state.destroy() }
        }
    }
    /** Only public identities already retained in this account's protected LSKF journal; never creates a pair. */
    fun knownMemberPublicKeys(): List<ByteArray> {
        verify()
        return local.withRecoverableVault(account.name, accountId) { load, _ ->
            val state = RecoverableVaultState.decode(load())
            try {
                state.attempt?.let { if (it.accountId != accountId || it.phase in setOf(
                    RecoverableVaultPhase.VAULT_LAUNCHED, RecoverableVaultPhase.JOIN_LAUNCHED)) throw PendingMutationException() }
                listOfNotNull(state.pairFor(domain)?.let(EnrollmentWire::publicKey)).also { verify() }
            } finally { state.destroy() }
        }
    }
    fun syncConsent(): RecoverableVaultStatus {
        if (!sdkMode) throw VaultLockedException()
        return perform(RecoverableVaultAction.REFRESH, consentSync = true)
    }
    fun perform(action: RecoverableVaultAction, attemptId: String? = null, consentSync: Boolean = false): RecoverableVaultStatus {
        verify()
        if (consentSync && !sdkMode) throw VaultLockedException()
        return local.withRecoverableVault(account.name, accountId) { loadBytes, saveBytes ->
            val opened = ArrayList<RecoverableVaultState>()
            fun load(): RecoverableVaultState { verify(); return RecoverableVaultState.decode(loadBytes()).also(opened::add) }
            fun save(value: RecoverableVaultState) {
                verify(); opened.add(value); val encoded = value.encode()
                try { saveBytes(encoded) } finally { encoded.fill(0) }
                verify()
            }
            val remote = object : RecoverableVaultRemote {
                override fun domain(domain: String, id: String): ByteArray {
                    verify()
                    return service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
                        name = DomainLifecycleWire.name(domain), view = 2, requestId = id)).encode().also { verify() }
                }
                override fun members(domain: String, id: String): List<ByteArray> {
                    return FolsomMemberPages(accountId, { verify(); accountId }) { page ->
                        verify()
                        service().ListSecurityDomainMembers().executeBlocking(ListSecurityDomainMembersRequest(parent = USERS,
                            filter = listOf(DomainLifecycleWire.name(domain)), memberSelection = 3, pageToken = page,
                            filterOptions = FilterOptions(includeDeleted = false), requestId = id)).also { verify() }
                    }.load().members.map { it.encode() }.also { verify() }
                }
                override fun vaults(id: String) = vault.list(id)
                override fun update(request: ByteArray) = vault.update(request)
                override fun join(request: ByteArray): ByteArray {
                    verify()
                    val body = JoinSecurityDomainRequest.ADAPTER.decode(request)
                    if (!request.contentEquals(body.encode())) throw ProtocolException()
                    return service().JoinSecurityDomain().executeBlocking(body).encode()
                }
            }
            val crypto = object : EnrollmentCrypto {
                override fun generatePair() = SecureBox.generateKeyPairBytes()
                override fun wrap(publicKey: ByteArray, material: ByteArray) = SecureBox.wrapKey(publicKey, material)
                override fun unwrap(pair: ByteArray, wrapped: ByteArray) = SecureBox.unwrapKey(pair, wrapped, "V1 shared_key".toByteArray())
            }
            try {
                val random = SecureRandom()
                val manager = RecoverableVaultManager(accountId, parent, domain, BuildConfig.VERSION_CODE, crypto,
                    RecoverableKeystore(context, vault::certificates, ::verify), remote, ::material, ::load, ::save, ::verify,
                    { RecoverableVaultWire.ALIAS_PREFIX + System.currentTimeMillis() + "/" +
                        ByteArray(16).also(random::nextBytes).joinToString("") { "%02X".format(it.toInt() and 255) } },
                    { val id = LastCheckinInfo.read(context).androidId
                        if (id == 0L) throw VaultLockedException()
                        RecoverableVaultWire.handle(random.nextLong(), id) })
                if (consentSync) manager.syncConsent() else when (action) {
                    RecoverableVaultAction.PREPARE -> manager.prepare()
                    RecoverableVaultAction.CONTINUE -> manager.continueOnce(attemptId ?: throw ProtocolException())
                    RecoverableVaultAction.REFRESH -> manager.reconcile()
                    RecoverableVaultAction.CLOSE -> manager.close(attemptId ?: throw ProtocolException())
                }
                status(load(), consentSync).also { verify() }
            } finally { opened.forEach(RecoverableVaultState::destroy) }
        }
    }
}
