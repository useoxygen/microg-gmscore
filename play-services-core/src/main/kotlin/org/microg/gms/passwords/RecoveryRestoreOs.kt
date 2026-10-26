/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.security.Key
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Actual OS ephemeral recovery session. No locally constructed claim, exported AES key, or software recovery fallback. */
internal class RecoveryRestoreOs(context: Context, private val authorize: () -> Unit) : RecoveryRestorePlatform {
    private val context = context.applicationContext
    private var session: Any? = null
    private fun call(target: Any, name: String, vararg args: Any): Any? {
        authorize()
        val method = target.javaClass.methods.singleOrNull { it.name == name && it.parameterTypes.size == args.size &&
            it.parameterTypes.indices.all { i -> it.parameterTypes[i].isInstance(args[i]) ||
                it.parameterTypes[i] == Integer.TYPE && args[i] is Int } } ?: throw VaultLockedException()
        return try { method.invoke(target, *args).also { authorize() } }
        catch (_: InvocationTargetException) { throw VaultLockedException() }
    }
    override fun start(vault: RecoveryRestoreVault, secretHash: ByteArray): ByteArray {
        if (session != null || secretHash.size != 32) throw ProtocolException()
        authorize()
        val controller = Class.forName("android.security.keystore.recovery.RecoveryController")
            .getMethod("getInstance", Context::class.java).invoke(null, context)
        val path = CertificateFactory.getInstance("X.509").generateCertPath(
            ByteArrayInputStream(Proto.decode(vault.metadata).one(7)), "PkiPath")
        val public = path.certificates.firstOrNull()?.publicKey as? ECPublicKey ?: throw ProtocolException()
        fun fixed(raw: ByteArray): ByteArray = if (raw.size <= 32) ByteArray(32 - raw.size) + raw else
            raw.takeIf { it.size == 33 && it[0] == 0.toByte() }?.copyOfRange(1, 33) ?: throw ProtocolException()
        val encoded = byteArrayOf(4) + fixed(public.w.affineX.toByteArray()) + fixed(public.w.affineY.toByteArray())
        if (!encoded.contentEquals(Proto.decode(vault.parameters).one(1))) throw ConflictException()
        val derivationClass = Class.forName("android.security.keystore.recovery.KeyDerivationParams")
        val derivation = derivationClass.getMethod("createSha256Params", ByteArray::class.java).invoke(null, byteArrayOf())
        val builder = Class.forName("android.security.keystore.recovery.KeyChainProtectionParams\$Builder").getConstructor().newInstance()
        call(builder, "setUserSecretType", 100); call(builder, "setLockScreenUiFormat", 2)
        call(builder, "setKeyDerivationParams", derivation); call(builder, "setSecret", secretHash)
        val protection = call(builder, "build") ?: throw ProtocolException()
        session = call(controller, "createRecoverySession") ?: throw VaultLockedException()
        // RecoverySession.start validates the supplied CertPath against the OS's actual trusted root.
        return try { call(session!!, "start", "GoogleCloudKeyVaultServiceV1", path, vault.verifierParams(),
            vault.challenge, listOf(protection)) as? ByteArray ?: throw ProtocolException() }
        finally { secretHash.fill(0) }
    }
    override fun recover(opened: ByteArray, vault: RecoveryRestoreVault): ByteArray =
        recoverDomains(opened, vault, listOf(vault.domain)).getValue(vault.domain)
    override fun recoverDomains(opened: ByteArray, vault: RecoveryRestoreVault, domains: List<String>): Map<String, ByteArray> {
        val active = session ?: throw VaultLockedException()
        if (domains.isEmpty() || domains.size > 2 || domains.toSet().size != domains.size) throw ProtocolException()
        val entries = domains.associateWith { Proto.decode(RecoveryRestoreVault(vault.encode(), it).entry) }
        val wrapped = entries.values.map { entry ->
            val builder = Class.forName("android.security.keystore.recovery.WrappedApplicationKey\$Builder").getConstructor().newInstance()
            call(builder, "setAlias", entry.string(1)); call(builder, "setEncryptedKeyMaterial", Proto.decode(entry.one(4)).one(3))
            call(builder, "build") ?: throw ProtocolException()
        }
        val keys = call(active, "recoverKeyChainSnapshot", Proto.decode(opened).one(4), wrapped) as? Map<*, *>
            ?: throw ProtocolException()
        val recovered = LinkedHashMap<String, ByteArray>()
        return try {
            entries.forEach { (domain, entry) ->
                val member = Proto.decode(entry.one(4))
                val key = keys[entry.string(1)] as? Key ?: throw VaultLockedException()
                val encrypted = member.one(2)
                try {
                    authorize()
                    val pair = Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                        doFinal(encrypted, 12, encrypted.size - 12)
                    }
                    try { EnrollmentWire.checkPair(pair)
                        if (!EnrollmentWire.publicKey(pair).contentEquals(member.one(1))) throw ConflictException()
                        authorize(); recovered[domain] = pair.copyOf()
                    } finally { pair.fill(0) }
                } finally { encrypted.fill(0) }
            }
            authorize(); recovered
        } catch (failure: Throwable) { recovered.values.forEach { it.fill(0) }; throw failure }
    }
    override fun close() {
        val active = session; session = null
        // Closing must still run after cancellation/revocation; it releases this specific OS session only.
        if (active != null) runCatching { active.javaClass.getMethod("close").invoke(active) }
    }
}
