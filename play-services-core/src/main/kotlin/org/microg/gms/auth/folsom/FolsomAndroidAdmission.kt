/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageAndCertHash
import org.microg.gms.common.hasGooglePackagePermission
import java.security.MessageDigest

internal object FolsomAndroidAdmission {
    fun identity(context: Context, packageName: String): FolsomCallerIdentity? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val uid = info.applicationInfo?.uid ?: return@runCatching null
        // External raw-key access requires the real current signer API. Same-UID callers
        // already share the process's authority on older systems.
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else
            if (uid == Process.myUid()) info.signatures else null
        val certificate = certificates?.singleOrNull()?.toByteArray() ?: return@runCatching null
        val sha256 = digest(certificate, "SHA-256")
        val permitted = listOf("SHA-256" to sha256, "SHA1" to digest(certificate, "SHA1")).any { (algorithm, hash) ->
            hasGooglePackagePermission(PackageAndCertHash(packageName, algorithm, hash), GooglePackagePermission.CREDENTIALS)
        }
        FolsomCallerIdentity(uid, packageName, sha256, permitted)
    }.getOrNull()
    fun account(context: Context, name: String): FolsomAccountFence {
        val id = accountId(context, name) ?: throw SecurityException("Recovery account unavailable")
        return FolsomAccountFence(name, id) { accountId(context, name) }.also { it.check() }
    }
    private fun accountId(context: Context, name: String): String? {
        val manager = AccountManager.get(context)
        val account = manager.getAccountsByType("com.google").singleOrNull { it.name == name } ?: return null
        // Cyclon's device account has no recovery keys for any client (CYC-927); the fence re-checks this every time.
        if (!DeviceAccount.mayShow(context, account)) return null
        return manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() }
    }
    private fun digest(value: ByteArray, algorithm: String): String = MessageDigest.getInstance(algorithm).digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
