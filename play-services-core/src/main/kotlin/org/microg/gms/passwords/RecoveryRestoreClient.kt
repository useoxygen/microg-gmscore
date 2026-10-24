/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.google.android.gms.BuildConfig
import com.squareup.wire.GrpcClient
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.*
import org.microg.gms.auth.folsom.utils.FolsomMemberKeys
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual OpenVault network failures have source SDK result9; no protocol, owner or proof failure is a success. */
internal class RecoveryRestoreFailure(val sdkResult: Int) : Exception("Recovery operation failed")
internal object RecoveryRestoreNetwork {
    fun <T> execute(open: () -> T): T = try { open() }
    catch (failure: GrpcException) {
        if (failure.grpcStatus in setOf(GrpcStatus.UNAVAILABLE, GrpcStatus.DEADLINE_EXCEEDED)) throw RecoveryRestoreFailure(9)
        throw failure
    } catch (_: java.io.IOException) { throw RecoveryRestoreFailure(9) }
}

internal class RecoveryRestoreChoice(val name: String, val digest: ByteArray, val format: Int, val remainingAttempts: Int?,
    val requestScopeDigest: ByteArray, val vaultType: Int = 1) {
    override fun toString() = "RecoveryRestoreChoice[redacted]"
}
internal data class RecoveryRestoreStatus(val phase: RecoveryRestorePhase?, val attemptId: String?, val resultCode: Int?,
    val requestScopeDigest: ByteArray? = null, val retryAfter: Long = 0L) {
    val complete: Boolean get() = phase == RecoveryRestorePhase.CONFIRMED
    val canFinish: Boolean get() = phase == RecoveryRestorePhase.RECOVERED
    val sdkResultCode: Int? get() = if (phase in setOf(RecoveryRestorePhase.CONFIRMED, RecoveryRestorePhase.REFUSED))
        resultCode?.let(RecoveryRestoreWire::sdkResult) else null
    fun acknowledges(expectedScope: ByteArray?): Boolean = complete && (expectedScope == null ||
        expectedScope.size == 32 && requestScopeDigest?.let { java.security.MessageDigest.isEqual(it, expectedScope) } == true)
}

/** Protected native restoration from a listed account vault, not an accepted-proof simulator or a raw-key export. */
internal class RecoveryRestoreClient(context: Context, private val account: Account, private val accountId: String,
    private val domain: String, expectedParent: String? = null, private val authorize: () -> Unit, private val sdkMode: Boolean = false) {
    private val context = context.applicationContext.also {
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
    }
    private val local = LocalKeyManager.getInstance(this.context)
    private val repository = PasswordRepository(this.context, account, expectedParent)
    private val parent = expectedParent ?: if (sdkMode) existingPasswordParent() else
        if (domain == "chromesync") repository.trustedVaultRecoveryParent(accountId) else null
    private var operationDomain = domain
    private val stopped = AtomicBoolean(false)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val transport = RecoverableVaultTransport(this.context, account, accountId, ::verify)
    init { DomainLifecycleWire.checkedDomain(domain); verify() }
    /** SDK recovery does not create/enable a Services vault, or turn unrelated local metadata into type5 authority. */
    private fun existingPasswordParent(): String? {
        if (!repository.enabled) return null
        val type5 = repository.store.locked { session ->
            val state = session.load(); val metadata = state.pendingMetadata ?: state.metadata
            metadata != null && Proto.decode(Proto.decode(metadata).one(3)).integer(2) == 5L
        }
        return if (type5) repository.trustedVaultRecoveryParent(accountId) else null
    }
    fun cancel() { stopped.set(true); transport.cancel(); http.dispatcher.cancelAll() }
    private fun verify() {
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context); val lock = context.getSystemService(KeyguardManager::class.java)
        if (!lock.isDeviceSecure || lock.isDeviceLocked || lock.isKeyguardLocked || account.type != "com.google" ||
            accountId.isBlank() || !accounts.getAccountsByType("com.google").contains(account) ||
            accounts.getUserData(account, "GoogleUserId") != accountId ||
            local.hasPendingDomainReset(account.name, "chromesync") || local.hasPendingDomainReset(account.name, "hw_protected")) throw VaultLockedException()
        if (parent != null && repository.trustedVaultRecoveryParent(accountId) != parent) throw VaultResetException()
    }
    private fun service(): SecurityDomainServiceClient {
        verify(); val token = context.requestOauthToken(account.name); verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun vaults(): List<ByteArray> = transport.list(java.util.UUID.randomUUID().toString(), 4).also { verify() }
    fun list(): List<RecoveryRestoreChoice> {
        verify()
        return vaults().mapNotNull { raw -> runCatching { RecoveryRestoreVault(raw, domain) }.getOrNull() }
            .filter { it.format in (if (it.handleType == 3) 1..2 else 1..3) }.map { RecoveryRestoreChoice(it.name, it.digest.copyOf(),
                it.format, it.remainingAttempts, it.requestScopeDigest.copyOf(), it.handleType) }
            .also { if (it.map { choice -> choice.digest.toByteString().base64() }.toSet().size != it.size) throw ProtocolException(); verify() }
    }
    private fun status(state: RecoveryRestoreState): RecoveryRestoreStatus {
        val a = state.attempt
        if (a != null && (a.accountId != accountId || a.domain != operationDomain)) throw VaultLockedException()
        return RecoveryRestoreStatus(if (a != null && a.parent != parent) RecoveryRestorePhase.REVOKED else a?.phase, a?.id, a?.resultCode,
            a?.let { RecoveryRestoreVault(it.vault, it.domain).requestScopeDigest.copyOf() }, a?.response?.let(RecoveryRestoreWire::retryAfter) ?: 0L)
    }
    fun status(): RecoveryRestoreStatus {
        verify()
        return local.withRecoveryRestore(account.name, accountId) { load, _ ->
            val state = RecoveryRestoreState.decode(load())
            try { status(state).also { verify() } } finally { state.destroy() }
        }
    }
    fun restore(digest: ByteArray, secret: CharArray): RecoveryRestoreStatus {
        try {
            verify()
            if (secret.size !in 1..1024) throw ProtocolException()
            val selected = vaults().mapNotNull { runCatching { RecoveryRestoreVault(it, domain) }.getOrNull() }
                .singleOrNull { java.security.MessageDigest.isEqual(it.digest, digest) } ?: throw ConflictException()
            val raw = RecoveryRestoreWire.secretBytes(selected.format, secret, selected.handleType)
            val hashed = try { RecoveryRestoreWire.hash(selected.metadata, raw) } finally { raw.fill(0); secret.fill('\u0000') }
            return try { operation(digest, hashed) } finally { hashed.fill(0) }
        } finally { secret.fill('\u0000') }
    }
    /** Actual RecoveryRequest.secretHash consumer. An admitted SDK caller transfers an owned32 copy without an Intent. */
    fun restoreHashed(digest: ByteArray, secretHash: ByteArray, challengeOverride: ByteArray? = null): RecoveryRestoreStatus = try {
        verify(); if (secretHash.size != 32) throw ProtocolException()
        operation(digest, secretHash, challengeOverride = challengeOverride)
    } finally { secretHash.fill(0) }
    private fun sdkSelection(scopeDigest: ByteArray, challenge: ByteArray): RecoveryRestoreVault {
        verify()
        if (!sdkMode || scopeDigest.size != 32 || challenge.size !in 1..65536) throw ProtocolException()
        return RecoveryRestoreWire.selectSdk(vaults(), domain, scopeDigest).also { operationDomain = it.domain; verify() }
    }
    fun restoreSdkHashed(scopeDigest: ByteArray, secretHash: ByteArray, originalChallenge: ByteArray): RecoveryRestoreStatus = try {
        if (secretHash.size != 32) throw ProtocolException()
        val selected = sdkSelection(scopeDigest, originalChallenge)
        operation(selected.digest, secretHash, challengeOverride = originalChallenge, allDomains = true)
    } finally { secretHash.fill(0) }
    fun generateSdkHashed(scopeDigest: ByteArray, secretHash: ByteArray, originalChallenge: ByteArray): ByteArray = try {
        if (secretHash.size != 32) throw ProtocolException()
        val selected = sdkSelection(scopeDigest, originalChallenge)
        generateHashed(selected.digest, secretHash, originalChallenge)
    } finally { secretHash.fill(0) }
    /** Source anqi generates a validation claim then closes the OS session; it does not consume a server guess here. */
    fun generateHashed(digest: ByteArray, secretHash: ByteArray, challengeOverride: ByteArray? = null): ByteArray = try {
        verify(); if (secretHash.size != 32 || digest.size != 32) throw ProtocolException()
        local.withRecoveryRestore(account.name, accountId) { load, _ ->
            val state = RecoveryRestoreState.decode(load())
            try {
                state.attempt?.let { if (it.phase !in setOf(RecoveryRestorePhase.CONFIRMED, RecoveryRestorePhase.REFUSED)) throw PendingMutationException() }
                val listed = vaults().mapNotNull { runCatching { RecoveryRestoreVault(it, operationDomain) }.getOrNull() }
                    .singleOrNull { java.security.MessageDigest.isEqual(it.digest, digest) } ?: throw ConflictException()
                val selected = challengeOverride?.let(listed::withChallenge) ?: listed
                RecoveryRestoreOs(context, ::verify).use { os ->
                    val claim = os.start(selected, secretHash)
                    try { verify(); RecoveryRestoreWire.request(selected, claim, java.util.UUID.randomUUID().toString(), BuildConfig.VERSION_CODE, 4) }
                    finally { claim.fill(0) }
                }
            } finally { state.destroy() }
        }
    } finally { secretHash.fill(0) }
    fun finishPending(expectedAttemptId: String, expectedScope: ByteArray? = null): RecoveryRestoreStatus =
        operation(expectedAttemptId = expectedAttemptId, expectedScope = expectedScope)
    fun close(id: String): RecoveryRestoreStatus = operation(close = id)
    private fun operation(digest: ByteArray? = null, hashed: ByteArray? = null, close: String? = null,
        challengeOverride: ByteArray? = null, allDomains: Boolean = false,
        expectedAttemptId: String? = null, expectedScope: ByteArray? = null): RecoveryRestoreStatus {
        verify()
        return local.withRecoveryRestore(account.name, accountId) { loadBytes, saveBytes ->
            val opened = ArrayList<RecoveryRestoreState>()
            fun load(): RecoveryRestoreState { verify(); return RecoveryRestoreState.decode(loadBytes()).also(opened::add) }
            fun save(value: RecoveryRestoreState) {
                verify(); opened.add(value); val raw = value.encode()
                try { saveBytes(raw) } finally { raw.fill(0) }; verify()
            }
            val expectedKeys = LinkedHashMap<String, List<Keys>>()
            val actualKeys = LinkedHashMap<String, List<Keys>>()
            val remote = object : RecoveryRestoreRemote {
                override fun vaults() = this@RecoveryRestoreClient.vaults()
                override fun open(request: ByteArray) = transport.open(request)
                override fun keys(domain: String, pair: ByteArray, id: String): List<DomainLifecycleKey> {
                    verify()
                    DomainLifecycleWire.checkedDomain(domain)
                    val before = service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
                        name = DomainLifecycleWire.name(domain), view = 2, requestId = id))
                    val epoch = before.currentEpoch?.takeIf { it > 0 } ?: throw VaultLockedException()
                    if (before.name != DomainLifecycleWire.name(domain)) throw ProtocolException()
                    verify()
                    val known = local.getKeysForDomain(account.name, domain)
                    val public = EnrollmentWire.publicKey(pair)
                    val name = EnrollmentWire.memberName(public)
                    val member = service().GetSecurityDomainMember().executeBlocking(GetSecurityDomainMemberRequest(name = name,
                        view = 2, filterOptions = FilterOptions(includeDeleted = false), requestId = id))
                    verify()
                    val attempt = load().attempt ?: throw ConflictException()
                    val expectedType = if (RecoveryRestoreVault(attempt.vault, attempt.domain).handleType == 3) 5 else 2
                    if (member.name != name || member.publicKey?.toByteArray()?.contentEquals(public) != true || member.memberType != expectedType) throw ConflictException()
                    val recovered = FolsomMemberKeys.decode(member, domain, pair, known)
                    if (recovered.none { it.keyVersion == epoch }) throw VaultLockedException()
                    val after = service().GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(
                        name = DomainLifecycleWire.name(domain), view = 2, requestId = id))
                    if (after.name != before.name || after.currentEpoch != epoch) throw VaultResetException()
                    verify(); expectedKeys[domain] = known; actualKeys[domain] = recovered
                    return recovered.map { value -> val raw = value.keyMaterial?.toByteArray() ?: throw ProtocolException()
                        try { DomainLifecycleKey(value.keyVersion ?: throw ProtocolException(), raw) } finally { raw.fill(0) } }
                }
            }
            val sink = object : RecoveryRestoreSink {
                override fun commit(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: List<DomainLifecycleKey>) {
                    commitDomains(before, after, mapOf(operationDomain to keys))
                }
                override fun commitDomains(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: Map<String, List<DomainLifecycleKey>>) {
                    verify()
                    if ("chromesync" in keys && parent != null) {
                        val pinned = parent
                        val recovered = keys.getValue("chromesync").map { key -> val raw = key.material
                            try { VersionedTrustedVaultKey(key.epoch, raw) } finally { raw.fill(0) } }
                        val raw = recovered.map { it.material }
                        try {
                            val metadata = PasswordApi(repository.sharingTransport()).metadata()
                            if (Proto.decode(metadata).string(1) != pinned) throw VaultResetException()
                            VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy(); verify()
                            // This local store is idempotent; RECOVERED remains durable if the later account-cache CAS fails.
                            repository.acceptVersionedTrustedVaultKeys(recovered, pinned, accountId, false, authorize)
                        } finally { raw.forEach { it.fill(0) }; recovered.forEach { it.destroy() } }
                    }
                    verify()
                    val expected = before.encode(); val replacement = after.encode()
                    try {
                        if (expectedKeys.keys != keys.keys || actualKeys.keys != keys.keys) throw ProtocolException()
                        local.commitRecoveryRestoreDomains(account.name, accountId, expected, replacement, expectedKeys, actualKeys, authorize)
                    }
                    finally { expected.fill(0); replacement.fill(0) }
                    opened.add(after); verify()
                }
            }
            try {
                RecoveryRestoreOs(context, ::verify).use { os ->
                    val manager = RecoveryRestoreManager(accountId, operationDomain, parent, BuildConfig.VERSION_CODE, remote, os, sink,
                        ::load, ::save, ::verify)
                    when { close != null -> manager.close(close)
                        digest != null && hashed != null -> manager.restore(digest, hashed, challengeOverride, allDomains)
                        else -> manager.finish(expectedAttemptId, expectedScope) }
                }
                status(load()).also { verify() }
            } finally { opened.forEach(RecoveryRestoreState::destroy) }
        }
    }
}
