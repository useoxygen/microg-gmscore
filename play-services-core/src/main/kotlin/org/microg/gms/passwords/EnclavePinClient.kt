/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.google.android.gms.BuildConfig
import com.squareup.wire.GrpcClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.microg.gms.auth.folsom.*
import org.microg.gms.auth.folsom.utils.FolsomMemberPages
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class EnclavePinStatus(val id: String?, val phase: EnclavePinPhase?, val registered: Boolean,
    val hasWrappedCurrentKey: Boolean, val hasPin: Boolean? = null) {
    override fun toString() = "EnclavePinStatus[redacted]"
}

/** Private native owner consumer for genuine enclave registration and set/change PIN on an existing hw_protected domain. */
internal class EnclavePinClient(context: Context, private val account: Account, private val accountId: String, private val authorize: () -> Unit) {
    private val context = context.applicationContext.also {
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
    }
    private val stopped = AtomicBoolean(false)
    private val local = LocalKeyManager.getInstance(this.context)
    private val enclave = EnclavePinTransport(this.context, account, ::verify)
    private val vault = RecoverableVaultTransport(this.context, account, accountId, ::verify)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private fun verify() {
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context); val guard = context.getSystemService(KeyguardManager::class.java)
        if (account.type != "com.google" || accountId.isBlank() || !guard.isDeviceSecure || guard.isDeviceLocked || guard.isKeyguardLocked ||
            !accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId ||
            local.hasPendingDomainReset(account.name, GpmPinRenewalWire.DOMAIN)) throw VaultLockedException()
    }
    fun cancel() { stopped.set(true); enclave.cancel(); vault.cancel(); http.dispatcher.cancelAll() }
    private fun service(): SecurityDomainServiceClient {
        verify(); val token = context.requestOauthToken(account.name); verify()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun keys(): List<DomainLifecycleKey> {
        verify(); val result = ArrayList<DomainLifecycleKey>()
        try {
            local.getKeysForDomain(account.name, GpmPinRenewalWire.DOMAIN).forEach { key ->
                val material = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                try { result.add(DomainLifecycleKey(key.keyVersion?.takeIf { it > 0 } ?: throw VaultLockedException(), material)) }
                finally { material.fill(0) }
            }
            if (result.isEmpty() || result.map { it.epoch }.toSet().size != result.size) throw VaultLockedException()
            verify(); return result
        } catch (failure: Throwable) { result.forEach(DomainLifecycleKey::close); throw failure }
    }
    private fun certificates(): Pair<ByteArray, ByteArray> {
        // Chrome's producer uses cert.xml + cert.sig.xml. Do not normalize the GMS10019 branch into this contract.
        fun fetch(path: String): ByteArray {
            verify()
            return http.newCall(Request.Builder().url("https://www.gstatic.com/cryptauthvault/v0/$path").build()).execute().use { response ->
                if (response.code != 200 || response.body == null) throw ProtocolException()
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                response.body!!.byteStream().use { stream -> while (true) { val count = stream.read(buffer); if (count < 0) break
                    if (count > 256 * 1024 - out.size()) throw ProtocolException(); out.write(buffer, 0, count) } }
                verify(); out.toByteArray().also { if (it.isEmpty()) throw ProtocolException() }
            }
        }
        val xml = fetch("cert.xml")
        return try { xml to fetch("cert.sig.xml") } catch (failure: Throwable) { xml.fill(0); throw failure }
        // The pinned+attested enclave validates this signed XML and returns its verified certificate path/serial.
    }
    private fun <T> session(action: (EnclavePinManager, () -> EnclavePinState) -> T): T {
        verify()
        return local.withEnclavePin(account.name, accountId) { loadBytes, saveBytes ->
            val opened = ArrayList<EnclavePinState>()
            fun load(): EnclavePinState { verify(); val bytes = loadBytes()
                return try { EnclavePinState.decode(bytes).also(opened::add) } finally { bytes.fill(0) } }
            fun save(state: EnclavePinState) { verify(); opened.add(state); val bytes = state.encode()
                try { saveBytes(bytes) } finally { bytes.fill(0) }; verify() }
            val remote = object : EnclavePinRemote {
                override fun transact(commands: ByteArray, signed: Boolean, rapt: String?): ByteArray {
                    verify(); val state = load()
                    val identity = if (!signed) null else EnclavePinIdentity(context, accountId, state.alias, state.spki, ::verify)
                    return enclave.transact(commands, identity, rapt).also { verify() }
                }
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
            try { action(EnclavePinManager(accountId, BuildConfig.VERSION_CODE, remote, ::keys, ::load, ::save, ::verify), ::load).also { verify() } }
            finally { opened.forEach(EnclavePinState::destroy) }
        }
    }
    private fun status(state: EnclavePinState, hasPin: Boolean? = null): EnclavePinStatus {
        if (state.attempt?.accountId?.let { it != accountId } == true) throw VaultLockedException()
        val material = keys()
        return try { EnclavePinStatus(state.attempt?.id, state.attempt?.phase, state.registered,
            state.wrappedSecret.isNotEmpty() && state.epoch == material.maxOf { it.epoch } && state.keysDigest.contentEquals(RecoverableVaultWire.keysDigest(material)), hasPin) }
        finally { material.forEach(DomainLifecycleKey::close) }
    }
    fun status(): EnclavePinStatus = session { _, load -> status(load()) }
    fun refresh(): EnclavePinStatus = session { manager, load -> manager.reconcile(); status(load(), manager.hasPin()) }
    fun continueOnce(id: String): EnclavePinStatus = session { manager, load -> manager.continueOnce(id); status(load(), manager.hasPin()) }
    fun close(id: String): EnclavePinStatus = session { manager, load -> manager.close(id); status(load(), manager.hasPin()) }
    /** PIN ownership transfers to this call. It is wiped even if registration, reauth, I/O or local authority fails. */
    fun setPin(pin: CharArray, change: Boolean, rapt: String): EnclavePinStatus {
        var bytes: ByteArray? = null
        try {
            verify()
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(pin))
            val input = ByteArray(encoded.remaining()); encoded.get(input)
            if (encoded.hasArray()) encoded.array().fill(0)
            bytes = input; pin.fill('\u0000')
            if (input.size !in 1..1024 || rapt.isBlank() || rapt.length > 16384) throw ProtocolException()
            return session { manager, load ->
                var state = load()
                if (state.attempt != null) throw PendingMutationException()
                // Check the requested operation before registering any new remote device.
                if (manager.hasPin() != change) throw ConflictException()
                if (!state.registered) {
                    if (state.alias.isEmpty()) { manager.prepareAlias(EnclavePinIdentity.newAlias(accountId)); state = load() }
                    val identity = EnclavePinIdentity(context, accountId, state.alias, state.spki.takeIf { it.isNotEmpty() }, ::verify)
                    val descriptor = identity.readStable(allowCreate = state.spki.isEmpty())
                    manager.register(descriptor.spki, descriptor.level)
                }
                manager.wrapCurrentKey()
                val certificate = certificates()
                try { manager.setPin(input, change, rapt, certificate) } finally { certificate.first.fill(0); certificate.second.fill(0) }
                status(load(), manager.hasPin())
            }
        } finally { pin.fill('\u0000'); bytes?.fill(0) }
    }
}
