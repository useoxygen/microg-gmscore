/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.utils.ExtendedPackageInfo
import java.security.MessageDigest
import java.util.Base64

@RequiresApi(28)
class PasswordAutofillTarget private constructor(val realm: String, val caller: ByteArray, private val packageName: String, private val certificate: ByteArray,
    val usernameId: AutofillId?, val passwordId: AutofillId, val username: String, val password: String) {
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
        private class Field(val node: AssistStructure.ViewNode, val domain: String?, val scheme: String?)
        fun parse(context: Context, structure: AssistStructure): PasswordAutofillTarget {
            val pkg = structure.activityComponent?.packageName ?: throw SecurityException()
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.size == 1 } ?: throw SecurityException()
            val certificate = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            val browser = pkg in setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary") &&
                ExtendedPackageInfo(context, pkg).hasGooglePackagePermission(GooglePackagePermission.CREDENTIALS)
            val fields = ArrayList<Field>()
            var visited = 0
            fun walk(node: AssistStructure.ViewNode, domain: String?, scheme: String?, depth: Int) {
                if (++visited > 5000 || depth > 64) throw ProtocolException()
                val host = node.webDomain ?: domain
                val protocol = node.webScheme ?: scheme
                if (node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) fields.add(Field(node, host, protocol))
                for (i in 0 until node.childCount) walk(node.getChildAt(i), host, protocol, depth + 1)
            }
            if (structure.windowNodeCount > 16) throw ProtocolException()
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, null, null, 0)
            val passwords = fields.filter { field ->
                val hints = field.node.autofillHints.orEmpty()
                val variant = field.node.inputType and InputType.TYPE_MASK_VARIATION
                val type = field.node.inputType and InputType.TYPE_MASK_CLASS
                View.AUTOFILL_HINT_PASSWORD in hints ||
                    type == InputType.TYPE_CLASS_TEXT && variant in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                    type == InputType.TYPE_CLASS_NUMBER && variant == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            val secret = passwords.singleOrNull() ?: throw ProtocolException()
            val users = fields.filter { field -> field !== secret && field.node.autofillHints.orEmpty().any {
                it == View.AUTOFILL_HINT_USERNAME || it == View.AUTOFILL_HINT_EMAIL_ADDRESS
            } }
            val user = users.singleOrNull()
            val realm = if (browser) {
                if (secret.domain.isNullOrEmpty() || secret.scheme != "https" ||
                    user != null && (user.domain != secret.domain || user.scheme != secret.scheme)) throw SecurityException()
                PasswordData.webRealm("https://${secret.domain}/")
            } else "android://${Base64.getUrlEncoder().withoutPadding().encodeToString(certificate)}@$pkg/"
            val caller = MessageDigest.getInstance("SHA-256").digest(Proto.field(1, pkg) + Proto.field(2, certificate) + Proto.field(3, realm))
            fun text(field: Field?) = field?.node?.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty()
            return PasswordAutofillTarget(realm, caller, pkg, certificate, user?.node?.autofillId,
                secret.node.autofillId ?: throw ProtocolException(), text(user), text(secret))
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
