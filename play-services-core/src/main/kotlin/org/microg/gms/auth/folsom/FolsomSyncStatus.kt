/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import android.content.Context
import android.os.Build
import org.microg.gms.auth.folsom.utils.FolsomConsentPolicy
import org.microg.gms.auth.folsom.utils.FolsomStaleKeyPolicy
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.passwords.*

/** anqp/anzf: source integer states distinguish wrapping, snapshot, consent and completed recovery sync. */
internal object FolsomSyncStatusPolicy {
    fun value(supported: Boolean, enabled: Boolean, wrappingReady: Boolean, handleReady: Boolean, consent: Boolean, synced: Boolean): Int = when {
        !supported -> 1
        !enabled -> 2
        !wrappingReady -> 3
        !handleReady -> 4
        !consent -> 5
        !synced -> 6
        else -> 7
    }
}
internal class FolsomSyncStatus(context: Context, private val accountName: String, private val accountId: String,
    private val domain: String, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val local = LocalKeyManager.getInstance(this.context)
    fun read(): Int {
        authorize()
        if (Build.VERSION.SDK_INT < 28) return 1
        val type = Class.forName("android.security.keystore.recovery.RecoveryController")
        val enabled = type.getMethod("isRecoverableKeyStoreEnabled", Context::class.java).invoke(null, context) as? Boolean ?: throw ProtocolException()
        authorize(); if (!enabled) return 2
        return local.withRecoverableVault(accountName, accountId) { load, _ ->
            val bytes = load(); val state = try { RecoverableVaultState.decode(bytes) } finally { bytes.fill(0) }
            try {
                val controller = type.getMethod("getInstance", Context::class.java).invoke(null, context)
                authorize()
                val snapshot = type.getMethod("getKeyChainSnapshot").invoke(controller)
                val alias = state.alias; val handle = state.handle
                val aliases = type.getMethod("getAliases").invoke(controller) as? List<*> ?: throw ProtocolException()
                val wrapped = snapshot?.javaClass?.getMethod("getWrappedApplicationKeys")?.invoke(snapshot) as? List<*>
                val wrappingReady = alias != null && alias in aliases && wrapped?.any { entry -> entry != null &&
                    entry.javaClass.getMethod("getAlias").invoke(entry) == alias } == true
                val serverParams = snapshot?.javaClass?.getMethod("getServerParams")?.invoke(snapshot) as? ByteArray
                val handleReady = handle != null && serverParams?.contentEquals(handle) == true
                // Read within this already-held account lock without acquiring another account lock.
                val consent = local.consentSnapshotUnderRecoveryLock(accountName, accountId).let { FolsomConsentPolicy.effective(it, domain) }
                val keys = local.getKeysForDomain(accountName, domain)
                val synced = state.attempt?.let { it.domain == domain && it.phase == RecoverableVaultPhase.CONFIRMED } == true &&
                    keys.isNotEmpty() && keys.all { (it.keyVersion ?: 0) > 0 } && local.getDomainStatus(accountName, domain) == DomainStatus.RECOVERABLE &&
                    !FolsomStaleKeyPolicy.stale(local.recoveryGeneration(accountName, domain, accountId)) && !local.hasPendingDomainReset(accountName, domain)
                authorize(); FolsomSyncStatusPolicy.value(true, true, wrappingReady, handleReady, consent, synced)
            } finally { state.destroy() }
        }
    }
}
