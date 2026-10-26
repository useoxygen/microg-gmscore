/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.squareup.wire.GrpcClient
import okhttp3.OkHttpClient
import org.microg.gms.auth.folsom.*
import org.microg.gms.auth.folsom.utils.FolsomMemberPages
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only public owner status leaves the account's protected Folsom session. */
internal data class RecoveryEnrollmentStatus(val parent: String, val attemptId: String?, val phase: EnrollmentPhase?)
internal enum class RecoveryEnrollmentAction { REFRESH, ENROLL, RETRY, CLOSE }

/** Existing chromesync domain only. A successful join reply alone never installs a recovery key. */
internal class PasswordRecoveryEnrollmentClient(context: Context, private val account: Account,
    private val expectedUserId: String, expectedParent: String?, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val repository = PasswordRepository(this.context, account, expectedParent)
    private val parent = expectedParent ?: repository.trustedVaultRecoveryParent(expectedUserId)
    // A launched join has one physical attempt. Neither OkHttp retry nor redirects may replay it.
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val passwordApi = PasswordApi(PasswordTransport { method, request ->
        verify(); repository.sharingTransport().invoke(method, request).also { verify() }
    })
    fun cancel() { cancelled.set(true); http.dispatcher.cancelAll() }
    fun status(): RecoveryEnrollmentStatus {
        verify()
        return LocalKeyManager.getInstance(context).withChromesyncEnrollment(account.name, expectedUserId) { session ->
            val state = session.load()
            RecoveryEnrollmentStatus(parent, state.attempt?.id, state.attempt?.phase).also { verify() }
        }
    }
    private fun verify() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context)
        if (!accounts.getAccountsByType("com.google").contains(account) ||
            accounts.getUserData(account, "GoogleUserId") != expectedUserId || !repository.enabled) throw VaultLockedException()
        if (repository.trustedVaultRecoveryParent(expectedUserId) != parent) throw VaultResetException()
    }
    /** Check the real password birthday/keybag as well as the locally pinned recovery grant. */
    private fun freshBaseline() {
        verify()
        val metadata = passwordApi.metadata()
        verify()
        if (Proto.decode(metadata).string(1) != parent) throw VaultResetException()
        val versioned = repository.versionedTrustedVaultKeys(parent, expectedUserId)
        val raw = ArrayList<ByteArray>()
        try {
            versioned.forEach { raw.add(it.material) }
            VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
            verify()
        } finally { raw.forEach { it.fill(0) }; versioned.forEach { it.destroy() } }
    }
    private fun service(): SecurityDomainServiceClient {
        verify()
        val token = context.requestOauthToken(account.name)
        verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build()
            .create(SecurityDomainServiceClient::class)
    }
    fun perform(action: RecoveryEnrollmentAction, attemptId: String? = null): RecoveryEnrollmentStatus {
        verify()
        return LocalKeyManager.getInstance(context).withChromesyncEnrollment(account.name, expectedUserId) { session ->
            verify()
            val remote = object : EnrollmentRemote {
                override fun domain(requestId: String): ByteArray {
                    freshBaseline()
                    val result = service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
                        name = EnrollmentWire.DOMAIN, view = 2, requestId = requestId))
                    freshBaseline(); return result.encode()
                }
                override fun members(requestId: String): List<ByteArray> {
                    freshBaseline()
                    val result = FolsomMemberPages(expectedUserId, { verify(); expectedUserId }) { token ->
                        verify()
                        service().ListSecurityDomainMembers().executeBlocking(ListSecurityDomainMembersRequest(
                            parent = USERS, filter = listOf(EnrollmentWire.DOMAIN), memberSelection = 3,
                            pageToken = token, filterOptions = FilterOptions(includeDeleted = false), requestId = requestId))
                            .also { verify() }
                    }.load().members
                    // The full list proves finite scope; the direct Get proves this actual member's current content.
                    val pair = session.load().pair ?: throw ProtocolException()
                    val name = EnrollmentWire.memberName(EnrollmentWire.publicKey(pair))
                    val exact = result.singleOrNull { it.name == name }?.let {
                        verify()
                        service().GetSecurityDomainMember().executeBlocking(GetSecurityDomainMemberRequest(name = name,
                            view = 2, filterOptions = FilterOptions(includeDeleted = false), requestId = requestId))
                            .also { member -> if (member.name != name) throw ProtocolException() }
                    }
                    freshBaseline()
                    return result.map { if (it.name == name) (exact ?: throw ProtocolException()).encode() else it.encode() }
                }
                override fun join(request: ByteArray): ByteArray {
                    // This check occurs after the durable LAUNCHED receipt and immediately before actual dispatch.
                    freshBaseline()
                    val decoded = JoinSecurityDomainRequest.ADAPTER.decode(request)
                    if (!decoded.encode().contentEquals(request)) throw ProtocolException()
                    val client = service(); verify()
                    val result = try { client.JoinSecurityDomain().executeBlocking(decoded) }
                    catch (_: Exception) { throw PasswordRpcException(2, true) }
                    // Return the Join result before any unrelated follow-up RPC can fail.
                    // The coordinator records that it returned, then verifies owner/baseline and exact readback.
                    return result.encode()
                }
            }
            val crypto = object : EnrollmentCrypto {
                override fun generatePair() = SecureBox.generateKeyPairBytes()
                override fun wrap(publicKey: ByteArray, material: ByteArray) = SecureBox.wrapKey(publicKey, material)
                override fun unwrap(pair: ByteArray, wrapped: ByteArray) = SecureBox.unwrapKey(pair, wrapped,
                    "V1 shared_key".toByteArray(Charsets.UTF_8))
            }
            val manager = EnrollmentManager(expectedUserId, parent,
                { verify(); repository.versionedTrustedVaultKeys(parent, expectedUserId) }, crypto, remote,
                { verify(); session.load() }, { state -> verify(); session.save(state); verify() }, ::verify, session::existingPair)
            when (action) {
                RecoveryEnrollmentAction.REFRESH -> manager.reconcile()
                RecoveryEnrollmentAction.ENROLL -> manager.enroll()
                RecoveryEnrollmentAction.RETRY -> manager.retry(attemptId ?: throw ProtocolException())
                RecoveryEnrollmentAction.CLOSE -> manager.close(attemptId ?: throw ProtocolException())
            }
            verify()
            val state = session.load()
            RecoveryEnrollmentStatus(parent, state.attempt?.id, state.attempt?.phase).also { verify() }
        }
    }
}
