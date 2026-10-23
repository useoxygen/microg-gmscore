/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.fido.fido2.api.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.fido.core.*
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import java.security.MessageDigest
import java.util.UUID

/** The real legacy GMS FIDO consumer uses the same account vault; no account key enters fido.db. */
object LegacySyncedPasskeyBackend : AccountPasskeyBackend {
    override suspend fun available(context: Context, options: RequestOptions, callerPackage: String, source: String): Boolean =
        withContext(Dispatchers.IO) { runCatching {
            if (Build.VERSION.SDK_INT < 28 || source !in setOf(AuthenticatorActivity.SOURCE_APP, AuthenticatorActivity.SOURCE_BROWSER) ||
                !supported(options) || !context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return@runCatching false
            LegacyPasskeyTicketStore.system(context)
            caller(context, options, callerPackage, source)
            SyncedPasskeyVault.accounts(context).isNotEmpty()
        }.getOrDefault(false) }
    override fun request(context: Context, options: RequestOptions, callerPackage: String, source: String): Intent {
        if (Build.VERSION.SDK_INT < 28 || !supported(options)) throw VaultLockedException()
        LegacyPasskeyTicketStore.system(context)
        val (signer, origin) = caller(context, options, callerPackage, source)
        return LegacyPasskeyTicketStore(context).issue(LegacyPasskeyRequest(callerPackage, signer, origin, source,
            options.type == RequestOptionsType.REGISTER, options.serializeToBytes()))
    }
    internal fun caller(context: Context, options: RequestOptions, packageName: String, source: String): Pair<ByteArray, String> {
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: throw VaultLockedException()
        val signer = MessageDigest.getInstance("SHA-256").digest(cert)
        val origin = getOrigin(context, options, packageName)
        if (source == AuthenticatorActivity.SOURCE_BROWSER) {
            if (options !is BrowserRequestOptions || !LegacyPasskeyBridgePolicy.browser(packageName, signer, origin)) throw VaultLockedException()
        } else if (source != AuthenticatorActivity.SOURCE_APP || options is BrowserRequestOptions) throw VaultLockedException()
        return signer to origin
    }
    internal fun supported(options: RequestOptions): Boolean {
        val ext = options.authenticationExtensions
        if (ext != null && listOf(ext.fidoAppIdExtension, ext.cableAuthenticationExtension, ext.userVerificationMethodExtension,
                ext.googleMultiAssertionExtension, ext.googleSessionIdExtension, ext.googleSilentVerificationExtension,
                ext.devicePublicKeyExtension, ext.googleTunnelServerIdExtension, ext.googleThirdPartyPaymentExtension,
                ext.simpleTransactionAuthorizationExtension, ext.hmacSecretExtension, ext.paymentExtension).any { it != null }) return false
        if (runCatching { prf(options) }.isFailure) return false
        val extensions = if (ext?.prfExtension == null) emptySet() else setOf("prf")
        return if (options.type == RequestOptionsType.REGISTER) {
            val value = options.registerOptions
            LegacyPasskeyBridgePolicy.supported(true, value.parameters.filter { it.typeAsString == "public-key" }.map { it.algorithmIdAsInteger },
                value.attestationConveyancePreferenceAsString, value.authenticatorSelection?.attachmentAsString, extensions)
        } else LegacyPasskeyBridgePolicy.supported(false, emptyList(), null, null, extensions)
    }
    internal fun prf(options: RequestOptions) = SyncedPasskeyPrf.legacy(options.authenticationExtensions?.prfExtension,
        options.type == RequestOptionsType.REGISTER)
}

internal class LegacyPasskeyRequest(val caller: String, val signer: ByteArray, val origin: String, val source: String,
    val create: Boolean, val options: ByteArray) {
    val digest = LegacyPasskeyBridgePolicy.requestDigest(caller, signer, source, origin, create, options)
    fun parsed(): RequestOptions = when (source to create) {
        AuthenticatorActivity.SOURCE_APP to true -> PublicKeyCredentialCreationOptions.deserializeFromBytes(options)
        AuthenticatorActivity.SOURCE_APP to false -> PublicKeyCredentialRequestOptions.deserializeFromBytes(options)
        AuthenticatorActivity.SOURCE_BROWSER to true -> BrowserPublicKeyCredentialCreationOptions.deserializeFromBytes(options)
        AuthenticatorActivity.SOURCE_BROWSER to false -> BrowserPublicKeyCredentialRequestOptions.deserializeFromBytes(options)
        else -> throw ProtocolException()
    }
    fun encode(): ByteArray = Proto.field(1, caller) + Proto.field(2, signer) + Proto.field(3, origin) + Proto.field(4, source) +
        Proto.integer(5, if (create) 1 else 0) + Proto.field(6, options) + Proto.field(7, digest)
    override fun toString() = "LegacyPasskeyRequest[redacted]"
    companion object {
        fun decode(raw: ByteArray): LegacyPasskeyRequest {
            val p = Proto.decode(raw)
            if (p.integer(5) !in setOf(0L, 1L)) throw ProtocolException()
            return LegacyPasskeyRequest(p.string(1), p.one(2), p.string(3), p.string(4), p.integer(5) == 1L, p.one(6)).also {
                if (!MessageDigest.isEqual(it.digest, p.one(7))) throw ProtocolException()
            }
        }
    }
}

internal class LegacyPasskeyTicketStore(private val context: Context) {
    private val store = AndroidPasskeyStore(context, "legacy-fido-tickets", {})
    fun issue(request: LegacyPasskeyRequest): Intent {
        system(context)
        val nonce = UUID.randomUUID().toString(); val now = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (boot < 0) throw VaultLockedException()
        store.locked { session ->
            val raw = session.load()
            val entries = try { Proto.decode(raw).bytes(1).map(Proto::decode).filter { fresh(it, now, boot) } } finally { raw.fill(0) }
            if (entries.size >= 256) throw ProtocolException()
            val ticket = Proto.field(1, nonce) + Proto.field(2, request.encode()) + Proto.integer(3, now) + Proto.integer(4, boot) +
                Proto.integer(5, Process.myUid().toLong())
            session.save(entries.fold(Proto.field(1, ticket)) { out, p -> out + Proto.field(1, p.encode()) })
        }
        return Intent(context, LegacySyncedPasskeyActivity::class.java).setData(Uri.parse("microg-fido-account:/request/$nonce"))
    }
    fun consume(uri: Uri?): LegacyPasskeyRequest = store.locked { session ->
        system(context)
        if (uri?.scheme != "microg-fido-account" || uri.pathSegments?.size != 2 || uri.pathSegments[0] != "request") throw VaultLockedException()
        val raw = session.load(); val entries = try { Proto.decode(raw).bytes(1).map(Proto::decode) } finally { raw.fill(0) }
        val ticket = entries.singleOrNull { it.string(1) == uri.pathSegments[1] } ?: throw VaultLockedException()
        session.save(entries.filter { it !== ticket }.fold(byteArrayOf()) { out, p -> out + Proto.field(1, p.encode()) })
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (!fresh(ticket, SystemClock.elapsedRealtime(), boot) || ticket.integer(5) != Process.myUid().toLong()) throw VaultLockedException()
        LegacyPasskeyRequest.decode(ticket.one(2))
    }
    private fun fresh(p: Proto, now: Long, boot: Long) = boot >= 0 && p.integer(4) == boot &&
        p.integer(3)?.let { now >= it && now - it < 120_000 } == true
    companion object {
        fun system(context: Context) {
            val info = context.packageManager.getApplicationInfo("com.google.android.gms", 0)
            if (context.packageName != "com.google.android.gms" || !LegacyPasskeyBridgePolicy.systemCaller(info.uid, Process.myUid(),
                    info.flags and ApplicationInfo.FLAG_SYSTEM != 0, context.packageManager.getPackagesForUid(info.uid).orEmpty().toSet()))
                throw VaultLockedException()
        }
    }
}
