/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import org.microg.gms.auth.folsom.utils.SecureBox
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.cert.CertPath
import javax.crypto.SecretKey

/** Public SystemApi reflection keeps ordinary SDK builds possible; Android enforces RECOVER_KEYSTORE/root trust. */
internal class RecoverableKeystore(context: Context, private val certificates: () -> Pair<ByteArray, ByteArray>,
    private val authorize: () -> Unit) : RecoverableVaultPlatform {
    private val controller: Any = Class.forName("android.security.keystore.recovery.RecoveryController")
        .getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
    private var initialized = false
    private fun call(target: Any, name: String, vararg args: Any): Any? {
        authorize()
        val method = target.javaClass.methods.singleOrNull { it.name == name && it.parameterTypes.size == args.size &&
            it.parameterTypes.indices.all { i -> val type = it.parameterTypes[i]
                type.isInstance(args[i]) || type == Integer.TYPE && args[i] is Int } } ?: throw VaultLockedException()
        return try { method.invoke(target, *args).also { authorize() } }
        catch (failure: InvocationTargetException) { throw VaultLockedException() }
    }
    override fun prepare(alias: String, handle: ByteArray) {
        if (!alias.startsWith(RecoverableVaultWire.ALIAS_PREFIX) || handle.size != 17) throw ProtocolException()
        if (!initialized) {
            val (cert, sig) = certificates()
            try { call(controller, "initRecoveryService", "GoogleCloudKeyVaultServiceV1", cert, sig) }
            finally { cert.fill(0); sig.fill(0) }
            call(controller, "setRecoverySecretTypes", intArrayOf(100))
            initialized = true
        }
        val aliases = call(controller, "getAliases") as? List<*> ?: throw VaultLockedException()
        if (alias !in aliases) call(controller, "generateKey", alias)
        if (call(controller, "getKey", alias) !is SecretKey) throw VaultLockedException()
        call(controller, "setServerParams", handle)
    }
    override fun snapshot(alias: String, handle: ByteArray): RecoverableVaultSnapshot? {
        val snapshot = call(controller, "getKeyChainSnapshot") ?: return null
        val params = call(snapshot, "getServerParams") as? ByteArray ?: throw ProtocolException()
        if (!params.contentEquals(handle)) return null
        val wrapped = (call(snapshot, "getWrappedApplicationKeys") as? List<*>)?.filterNotNull()
            ?.filter { call(it, "getAlias") == alias }?.singleOrNull() ?: return null
        val encrypted = call(wrapped, "getEncryptedKeyMaterial") as? ByteArray ?: throw ProtocolException()
        val certPath = call(snapshot, "getTrustedHardwareCertPath") as? CertPath ?: throw ProtocolException()
        val key = certPath.certificates.firstOrNull()?.publicKey as? ECPublicKey ?: throw ProtocolException()
        fun coordinate(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            if (raw.size > 33) throw ProtocolException()
            val unsigned = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
            if (unsigned.size > 32) throw ProtocolException()
            return ByteArray(32 - unsigned.size) + unsigned
        }
        val public = byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
        SecureBox.deserializePublicKey(public) // Exact P256 point validation, not arbitrary certificate curve acceptance.
        if (key.params.curve.field.fieldSize != 256) throw ProtocolException()
        val protection = (call(snapshot, "getKeyChainProtectionParams") as? List<*>)?.filterNotNull()?.singleOrNull()
            ?: throw ProtocolException()
        val derivation = call(protection, "getKeyDerivationParams") ?: throw ProtocolException()
        val encoded = RecoverableVaultWire.protection(call(protection, "getLockScreenUiFormat") as Int,
            call(derivation, "getAlgorithm") as Int, call(derivation, "getSalt") as ByteArray,
            call(derivation, "getMemoryDifficulty") as Int, certPath.getEncoded("PkiPath"))
        return RecoverableVaultSnapshot(call(snapshot, "getSnapshotVersion") as Int, public,
            call(snapshot, "getCounterId") as Long, call(snapshot, "getMaxAttempts") as Int,
            params, encoded, call(snapshot, "getEncryptedRecoveryKeyBlob") as ByteArray, encrypted)
    }
    override fun wrap(alias: String, pair: ByteArray): ByteArray {
        EnrollmentWire.checkPair(pair)
        val key = call(controller, "getKey", alias) as? SecretKey ?: throw VaultLockedException()
        authorize()
        return RecoverableVaultWire.wrapPair(key, pair).also { authorize() }
    }
    override fun synced(alias: String) {
        if (call(controller, "getRecoveryStatus", alias) != 3) call(controller, "setRecoveryStatus", alias, 0)
    }
}
