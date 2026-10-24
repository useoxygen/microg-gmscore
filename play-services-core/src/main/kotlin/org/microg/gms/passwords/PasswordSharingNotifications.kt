/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.R
import com.google.android.gms.credential.manager.EXTRA_KEY_ACCOUNT_NAME
import com.google.android.gms.credential.manager.PASSWORD_MANAGER_CLASS_NAME
import java.security.MessageDigest

/** Post-commit notification only. Sender avatars remain metadata and never cause a network fetch. */
class PasswordSharingNotifications(context: Context, private val account: Account, private val userId: String) {
    private val context = context.applicationContext
    fun received(operation: SharingOperation) {
        if (operation.kind != SharingOperationKind.AUTOMATIC || operation.phase != SharingOperationPhase.COMPLETE) return
        val receipt = operation.receipt?.let(Proto::decode) ?: return
        if ((receipt.integer(1) ?: 0) <= 0) return
        val guid = operation.invitationGuid ?: return
        val realm = receipt.bytes(2).firstOrNull()?.let(Proto::utf8) ?: return
        val accounts = AccountManager.get(context)
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != userId) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val sender = operation.passwords.firstOrNull()?.let { Proto.decode(Proto.decode(it).one(1)).optionalString(24) }
            .orEmpty().filterNot { it.isISOControl() }.take(128)
        val id = MessageDigest.getInstance("SHA-256").digest((userId + ":" + guid).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val intent = Intent().setClassName(context, PASSWORD_MANAGER_CLASS_NAME)
            .setData(Uri.Builder().scheme("microg-password-sharing").authority("received").appendPath(id).build())
            .putExtra(EXTRA_KEY_ACCOUNT_NAME, account.name).putExtra("google_user_id", userId)
            .putExtra("pwm.DataFieldNames.detailsScreenAsStartScreen", true)
            .putExtra("pwm.DataFieldNames.deepLinkToSignonRealm", realm)
        val pending = PendingIntentCompat.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.passwords_sharing_notification_channel), NotificationManager.IMPORTANCE_HIGH)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val text = context.getString(if (sender.isEmpty()) R.string.passwords_sharing_notification_received else R.string.passwords_sharing_notification_sender, sender)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_manage_accounts)
            .setContentTitle(context.getString(R.string.passwords_sharing_notification_title)).setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(pending).addAction(0, context.getString(R.string.passwords_sharing_notification_manage), pending).build()
        // No password, username, realm, account label or avatar is rendered outside the owner gate.
        NotificationManagerCompat.from(context).notify(id, 0, notification)
    }
    private companion object { const val CHANNEL = "com.google.android.gms.notifications::password_sharing" }
}
