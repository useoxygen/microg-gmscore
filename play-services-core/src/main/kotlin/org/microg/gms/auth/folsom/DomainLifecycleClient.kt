/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.squareup.wire.GrpcClient
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import okhttp3.OkHttpClient
import org.microg.gms.auth.folsom.utils.FolsomMemberPages
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.gcm.AuthHeaderInterceptor
import org.microg.gms.passwords.*
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class DomainLifecycleOwnerStatus(val domain: String, val epoch: Int, val members: Int, val digest: ByteArray,
    val operationId: String?, val operation: DomainLifecycleKind?, val phase: DomainLifecyclePhase?, val authorityCleared: Boolean)

/** Native account-domain consumer for explicit owner lifecycle decisions, never background destructive repair. */
internal class DomainLifecycleClient(context: Context, private val account: Account, private val accountId: String,
    private val domain: String, private val expectedParent: String?, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val repository = PasswordRepository(this.context, account, expectedParent)
    private val local = LocalKeyManager.getInstance(this.context)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    init { DomainLifecycleWire.checkedDomain(domain); verify() }
    fun cancel() { stopped.set(true); http.dispatcher.cancelAll() }
    private fun verify() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context)
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId)
            throw VaultLockedException()
        if (expectedParent != null && repository.trustedVaultRecoveryParent(accountId) != expectedParent) throw VaultResetException()
    }
    private fun service(): SecurityDomainServiceClient {
        verify(); val token = context.requestOauthToken(account.name); verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun domain(requestId: String): ByteArray? {
        verify()
        val response = try { service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
            name = DomainLifecycleWire.name(domain), view = 2, requestId = requestId)).encode() }
        catch (failure: GrpcException) { if (failure.grpcStatus == GrpcStatus.NOT_FOUND) null else throw failure }
        verify(); return response
    }
    private fun members(selection: Int, id: String, absent: Boolean): List<ByteArray> {
        return try {
            FolsomMemberPages(accountId, { verify(); accountId }) { token ->
                verify()
                service().ListSecurityDomainMembers().executeBlocking(ListSecurityDomainMembersRequest(parent = USERS,
                    filter = listOf(DomainLifecycleWire.name(domain)), memberSelection = selection, pageToken = token,
                    filterOptions = FilterOptions(includeDeleted = false), requestId = id)).also { verify() }
            }.load().members.map { it.encode() }
        } catch (failure: GrpcException) {
            if (absent && failure.grpcStatus == GrpcStatus.NOT_FOUND) emptyList() else throw failure
        }
    }
    private val remote = object : DomainLifecycleRemote {
        override fun snapshot(domain: String, requestId: String): DomainLifecycleSnapshot {
            if (domain != this@DomainLifecycleClient.domain) throw ProtocolException()
            val before = this@DomainLifecycleClient.domain(requestId)
            val all = members(3, requestId, before == null)
            val recipients = members(2, requestId, before == null) // anwc.E(domain,4), hzxs.a(4)==2.
            val after = this@DomainLifecycleClient.domain(requestId)
            if ((before == null) != (after == null) || before != null && after != null &&
                DomainLifecycleWire.epoch(DomainLifecycleSnapshot(before, emptyList(), emptyList()), domain) !=
                DomainLifecycleWire.epoch(DomainLifecycleSnapshot(after, emptyList(), emptyList()), domain)) throw ConflictException()
            verify(); return DomainLifecycleSnapshot(after, all, recipients)
        }
        override fun invoke(kind: DomainLifecycleKind, request: ByteArray): ByteArray {
            verify()
            val client = service()
            // Every encoded request is canonical and persisted before this one no-retry physical RPC.
            return when (kind) {
                DomainLifecycleKind.CREATE, DomainLifecycleKind.ENROLL, DomainLifecycleKind.ADD_MEMBER -> {
                    val body = JoinSecurityDomainRequest.ADAPTER.decode(request)
                    if (!request.contentEquals(body.encode())) throw ProtocolException()
                    client.JoinSecurityDomain().executeBlocking(body).encode()
                }
                DomainLifecycleKind.RESET -> {
                    val body = ResetSecurityDomainRequest.ADAPTER.decode(request)
                    if (!request.contentEquals(body.encode())) throw ProtocolException()
                    client.ResetSecurityDomain().executeBlocking(body).encode()
                }
                DomainLifecycleKind.ROTATE -> {
                    val body = RotateSharedKeyRequest.ADAPTER.decode(request)
                    if (!request.contentEquals(body.encode())) throw ProtocolException()
                    client.RotateSharedKey().executeBlocking(body).encode()
                }
            } // Follow-up failures are handled after the coordinator knows the mutation returned.
        }
    }
    private val crypto = object : DomainLifecycleCrypto {
        override fun generatePair() = SecureBox.generateKeyPairBytes()
        override fun generateSharedKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        override fun wrap(publicKey: ByteArray, material: ByteArray) = SecureBox.wrapKey(publicKey, material)
        override fun unwrap(pair: ByteArray, wrapped: ByteArray) = SecureBox.unwrapKey(pair, wrapped, "V1 shared_key".toByteArray())
        override fun rotationProof(previous: ByteArray, next: ByteArray) = SecureBox.wrapShared(previous, next, byteArrayOf(0))
    }
    private fun keys(): List<DomainLifecycleKey> {
        verify()
        if (domain == "chromesync") {
            val parent = expectedParent ?: repository.trustedVaultRecoveryParent(accountId)
            val retained = repository.versionedTrustedVaultKeys(parent, accountId)
            val raw = ArrayList<ByteArray>()
            val result = ArrayList<DomainLifecycleKey>()
            try {
                retained.forEach { raw.add(it.material) }
                val metadata = PasswordApi(repository.sharingTransport()).metadata()
                verify()
                if (Proto.decode(metadata).string(1) != parent) throw VaultResetException()
                VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
                retained.forEachIndexed { i, key -> result.add(DomainLifecycleKey(key.epoch, raw[i])) }
                verify(); return result
            } catch (failure: Throwable) { result.forEach { it.close() }; throw failure }
            finally { raw.forEach { it.fill(0) }; retained.forEach { it.destroy() } }
        }
        val result = ArrayList<DomainLifecycleKey>()
        try {
            local.getKeysForDomain(account.name, domain).forEach { key ->
                val raw = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                try { result.add(DomainLifecycleKey(key.keyVersion?.takeIf { it > 0 } ?: throw VaultLockedException(), raw)) }
                finally { raw.fill(0) }
            }
            verify(); return result
        } catch (failure: Throwable) { result.forEach { it.close() }; throw failure }
    }
    fun localStatus(): DomainLifecycleOwnerStatus = local.withDomainLifecycle(account.name, accountId, domain) { session ->
        verify()
        val operation = session.load().attempt
        val baseline = operation?.baseline?.let(DomainLifecycleSnapshot::decode) ?: DomainLifecycleSnapshot(null, emptyList(), emptyList())
        DomainLifecycleOwnerStatus(domain, DomainLifecycleWire.epoch(baseline, domain), baseline.members.size, baseline.digest(),
            operation?.id, operation?.kind, operation?.phase, operation?.authorityCleared == true).also { verify() }
    }
    /** action null is read-only reconciliation; retry and closure require the reviewed exact operation ID. */
    fun perform(action: DomainLifecycleKind? = null, digest: ByteArray? = null, retry: String? = null, close: String? = null,
        externalPublic: ByteArray? = null, joinOptions: Int = 0): DomainLifecycleOwnerStatus {
        verify()
        externalPublic?.let { SecureBox.deserializePublicKey(it) }
        return local.withDomainLifecycle(account.name, accountId, domain) { session ->
            val manager = DomainLifecycleManager(accountId, domain, crypto, remote,
                { verify(); session.load() }, { state -> verify(); session.save(state); verify() }, ::keys, ::verify, session::existingPair)
            when {
                retry != null -> manager.retry(retry, digest ?: throw ProtocolException())
                close != null -> manager.close(close)
                action != null -> manager.prepare(action, digest ?: throw ProtocolException(), externalPublic, joinOptions)
                else -> manager.reconcile()
            }
            var state = session.load()
            val operation = state.attempt
            if (operation?.kind == DomainLifecycleKind.RESET && operation.phase == DomainLifecyclePhase.COMMITTED && !operation.authorityCleared) {
                if (domain == "chromesync") repository.clearTrustedVaultRecovery(expectedParent, accountId, ::verify)
                verify(); manager.acknowledgeResetAuthorityCleared(operation.id); state = session.load()
            }
            val snapshot = manager.review()
            verify()
            DomainLifecycleOwnerStatus(domain, DomainLifecycleWire.epoch(snapshot, domain), snapshot.members.size, snapshot.digest(),
                state.attempt?.id, state.attempt?.kind, state.attempt?.phase, state.attempt?.authorityCleared == true)
        }
    }
}
