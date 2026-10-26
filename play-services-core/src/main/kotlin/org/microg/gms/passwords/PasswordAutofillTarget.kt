/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.utils.ExtendedPackageInfo
import java.security.MessageDigest
import java.util.Base64

@RequiresApi(28)
class PasswordAutofillTarget private constructor(val realm: String, val caller: ByteArray, private val packageName: String, private val certificate: ByteArray,
    val usernameId: AutofillId?, val passwordId: AutofillId, val username: String, val password: String, val isBrowser: Boolean) {
    fun payload(save: Boolean): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(Bundle().apply {
                putParcelable("usernameId", usernameId); putParcelable("passwordId", passwordId)
                putString("package", packageName); putByteArray("certificate", certificate)
                if (save) { putString("username", username); putString("password", password) }
            })
            parcel.marshall()
        } finally { parcel.recycle() }
    }
    companion object {
        fun parse(context: Context, structure: AssistStructure): PasswordAutofillTarget {
            val pkg = structure.activityComponent?.packageName ?: throw SecurityException()
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.size == 1 } ?: throw SecurityException()
            val certificate = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            val browser = pkg in setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary") &&
                ExtendedPackageInfo(context, pkg).hasGooglePackagePermission(GooglePackagePermission.CREDENTIALS)
            val fields = ArrayList<AssistStructure.ViewNode>()
            var visited = 0
            fun walk(node: AssistStructure.ViewNode, depth: Int) {
                if (++visited > 5000 || depth > 64) throw ProtocolException()
                if (node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) fields.add(node)
                for (i in 0 until node.childCount) walk(node.getChildAt(i), depth + 1)
            }
            if (structure.windowNodeCount > 16) throw ProtocolException()
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, 0)
            val passwords = fields.filter { PasswordAutofillField.kind(it, browser) == PasswordAutofillField.Kind.PASSWORD }
            val secret = passwords.singleOrNull() ?: throw ProtocolException()
            val users = fields.filter { it !== secret && PasswordAutofillField.kind(it, browser) == PasswordAutofillField.Kind.USERNAME }
            val user = users.singleOrNull()
            val realm = if (browser) browserRealm(secret, user)
                else "android://${Base64.getUrlEncoder().withoutPadding().encodeToString(certificate)}@$pkg/"
            val caller = MessageDigest.getInstance("SHA-256").digest(Proto.field(1, pkg) + Proto.field(2, certificate) + Proto.field(3, realm))
            fun text(field: AssistStructure.ViewNode?) = field?.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty()
            return PasswordAutofillTarget(realm, caller, pkg, certificate, user?.autofillId,
                secret.autofillId ?: throw ProtocolException(), text(user), text(secret), browser)
        }
        internal fun browserRealm(secret: AssistStructure.ViewNode, user: AssistStructure.ViewNode?): String {
            // Chrome can flatten fields from several frames under a single form. The form's
            // domain is not authority for its child inputs. Older Chrome/disabled forwarding
            // may omit field origins; refuse instead of assigning the parent site's password.
            val domain = secret.webDomain
            if (domain.isNullOrEmpty() || secret.webScheme != "https" ||
                user != null && (user.webDomain != domain || user.webScheme != "https")) throw SecurityException()
            // Android exposes only scheme + host here, not the port. This remains an
            // owner-authenticated autofill path; it is not an exact-origin agent grant.
            val uri = java.net.URI("https://$domain/")
            if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.rawAuthority != domain || uri.port != -1 ||
                uri.rawPath != "/" || uri.rawQuery != null || uri.rawFragment != null) throw SecurityException()
            return PasswordData.webRealm(uri.toString())
        }
        fun unpack(payload: ByteArray): Bundle {
            if (payload.size > 512 * 1024) throw ProtocolException()
            val parcel = Parcel.obtain()
            return try {
                parcel.unmarshall(payload, 0, payload.size); parcel.setDataPosition(0)
                parcel.readBundle(AutofillId::class.java.classLoader) ?: throw ProtocolException()
            } finally { parcel.recycle() }
        }
        fun recheck(context: Context, payload: Bundle, realm: String): ByteArray {
            val pkg = payload.getString("package") ?: throw SecurityException()
            val signers = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?.apkContentsSigners?.takeIf { it.size == 1 } ?: throw SecurityException()
            val certificate = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            if (!MessageDigest.isEqual(certificate, payload.getByteArray("certificate"))) throw SecurityException()
            return MessageDigest.getInstance("SHA-256").digest(Proto.field(1, pkg) + Proto.field(2, certificate) + Proto.field(3, realm))
        }
    }
}
