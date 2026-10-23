/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import org.microg.gms.auth.folsom.utils.FolsomDomainPolicy

/** A snapshot of the current APK signer, never the legacy signature-spoofing view or signing history. */
internal class FolsomCallerIdentity(val uid: Int, val packageName: String, val certificate: String, val credentialsPermission: Boolean) {
    override fun toString(): String = "FolsomCallerIdentity[redacted]"
}

internal class FolsomCallerAdmission(private val captured: FolsomCallerIdentity, private val domain: String, private val serviceUid: Int) {
    val sameUid: Boolean get() = captured.uid == serviceUid
    init { if (!allowed(captured, domain, serviceUid)) throw SecurityException("Recovery caller unavailable") }
    fun checkTransaction(callingUid: Int, current: FolsomCallerIdentity?) {
        if (callingUid != captured.uid) throw SecurityException("Recovery connection owner changed")
        checkCurrent(current)
    }
    /** Called again on the coroutine thread immediately before any result is released. */
    fun checkCurrent(current: FolsomCallerIdentity?) {
        if (current == null || current.uid != captured.uid || current.packageName != captured.packageName ||
            current.certificate != captured.certificate || !allowed(current, domain, serviceUid))
            throw SecurityException("Recovery caller changed")
    }
    companion object {
        // Pinned jbkw.t / KeyRetrievalApiChimeraService admits these Chrome package names.
        private val chrome = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
        fun allowed(identity: FolsomCallerIdentity, domain: String, serviceUid: Int): Boolean =
            FolsomDomainPolicy.valid(domain) && identity.uid >= 0 && identity.packageName.isNotBlank() &&
                Regex("[0-9a-f]{64}").matches(identity.certificate) &&
                (identity.uid == serviceUid || domain == "chromesync" && identity.packageName in chrome && identity.credentialsPermission)
    }
}

internal class FolsomAccountFence(val name: String, val userId: String, private val current: () -> String?) {
    init { if (name.isBlank() || userId.isBlank()) throw SecurityException("Recovery account unavailable") }
    fun check() { if (current() != userId) throw SecurityException("Recovery account changed") }
    fun <T> checked(action: () -> T): T { check(); val result = action(); check(); return result }
    override fun toString(): String = "FolsomAccountFence[redacted]"
}
