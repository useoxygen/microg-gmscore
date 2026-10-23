/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.UUID

/** Chrome145 identity signing only. User-verifying keys are a separate, unadvertised capability. */
internal class EnclavePinIdentity(context: Context, private val accountId: String, private val alias: String,
    expectedSpki: ByteArray?, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val expected = expectedSpki?.copyOf()
    init {
        require(accountId.isNotBlank() && accountId.length <= 256)
        val suffix = alias.removePrefix(prefix(accountId))
        if (!alias.startsWith(prefix(accountId)) || runCatching { UUID.fromString(suffix).toString() }.getOrNull() != suffix ||
            expected?.size?.let { it !in 65..4096 } == true) throw VaultLockedException()
    }
    internal class Descriptor(val alias: String, spki: ByteArray, val level: String) {
        private val public = spki.copyOf()
        val spki: ByteArray get() = public.copyOf()
        val deviceId: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(public)
        override fun toString() = "EnclavePinIdentityDescriptor[redacted]"
    }
    private fun verify() {
        authorize()
        if (Thread.currentThread().isInterrupted) throw VaultLockedException()
        val accounts = AccountManager.get(context)
        if (accounts.getAccountsByType("com.google").none { accounts.getUserData(it, "GoogleUserId") == accountId })
            throw VaultLockedException()
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** allowCreate is only for an already durable new-identity journal. Existing bound keys are never replaced. */
    fun readStable(allowCreate: Boolean = false): Descriptor {
        verify()
        if (expected == null && !allowCreate) throw VaultLockedException()
        val keys = store()
        if (!keys.containsAlias(alias)) {
            if (!allowCreate || expected != null) throw VaultLockedException()
            verify()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
                generateKeyPair()
            }
            verify()
        }
        val key = keys.getKey(alias, null) as? PrivateKey ?: throw VaultLockedException()
        val public = keys.getCertificate(alias)?.publicKey as? ECPublicKey ?: throw VaultLockedException()
        val params = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
        }
        if (public.params.curve != params.curve || public.params.order != params.order || public.params.generator != params.generator)
            throw VaultLockedException()
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        if (info.keystoreAlias != alias || info.keySize != 256 || info.origin != KeyProperties.ORIGIN_GENERATED ||
            (info.purposes and KeyProperties.PURPOSE_SIGN) == 0 || KeyProperties.DIGEST_SHA256 !in info.digests ||
            info.isUserAuthenticationRequired) throw VaultLockedException()
        val level = if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_STRONGBOX,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "hw"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "sw"
            else -> throw VaultLockedException()
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "hw" else "sw"
        }
        val spki = public.encoded
        if (spki.size !in 65..4096 || expected?.let { !MessageDigest.isEqual(it, spki) } == true) throw VaultLockedException()
        return Descriptor(alias, spki, level).also { verify() }
    }

    /** Exact Chrome145 signed message: handshakeHash32 || SHA256(exact CBOR commands)32; JCA emits DER ECDSA. */
    fun sign(message: ByteArray): ByteArray {
        if (message.size != 64 || expected == null) throw ProtocolException()
        val bytes = message.copyOf()
        var result: ByteArray? = null
        try {
            val before = readStable()
            verify()
            val keys = store()
            val key = keys.getKey(alias, null) as? PrivateKey ?: throw VaultLockedException()
            val signed = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
            result = signed
            verify()
            val after = readStable()
            if (before.level != after.level || !MessageDigest.isEqual(before.spki, after.spki)) throw VaultLockedException()
            val valid = Signature.getInstance("SHA256withECDSA").run {
                initVerify(keys.getCertificate(alias).publicKey); update(bytes); verify(signed)
            }
            if (!valid) throw VaultLockedException()
            verify()
            return signed.also { result = null }
        } finally { bytes.fill(0); result?.fill(0) }
    }
    override fun toString() = "EnclavePinIdentity[redacted]"
    companion object {
        private const val ALIAS_PREFIX = "microg.folsom.enclave.identity.v1."
        private fun marker(accountId: String) = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun prefix(accountId: String) = ALIAS_PREFIX + marker(accountId) + "."
        fun newAlias(accountId: String): String {
            if (accountId.isBlank() || accountId.length > 256) throw VaultLockedException()
            return prefix(accountId) + UUID.randomUUID().toString()
        }
        /** Only this feature's absent-account identity aliases are revoked. Discovery must be complete and stable. */
        fun purgeRemovedAccounts(context: Context) {
            val accounts = AccountManager.get(context)
            fun discover() = accounts.getAccountsByType("com.google").map { account ->
                accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
            }.toSet()
            val current = discover()
            val active = current.map(::marker).toSet()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = keys.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }
            for (alias in aliases) {
                if (discover() != current) throw VaultLockedException()
                val owner = alias.removePrefix(ALIAS_PREFIX).substringBefore('.')
                if (owner !in active) keys.deleteEntry(alias)
            }
        }
    }
}
