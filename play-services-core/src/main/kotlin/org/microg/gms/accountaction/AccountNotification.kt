// Modified by Cyclon in 2026: notice for work accounts whose management mode is unsupported; the action notice is
// tagged and cleared once Google issues the account a token.
package org.microg.gms.accountaction

import android.Manifest
import android.accounts.Account
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import com.google.android.gms.R
import org.microg.gms.auth.login.LoginActivity
import org.microg.gms.ui.MainSettingsActivity

private const val CHANNEL_ID = "AccountNotification"

/** Channel for account notifications that need the user, registered by [registerAccountNotificationChannel]. */
const val ACCOUNT_NOTIFICATION_CHANNEL_ID = CHANNEL_ID

/**
 * Tag of "Account action required". The re-auth and unsupported-management notices share its id (the account's hash),
 * so clearing this one by tag never removes those.
 */
internal const val ACCOUNT_ACTION_TAG = "account_action"

@RequiresApi(21)
fun Context.sendAccountReAuthNotification(account: Account) {
    Log.d(TAG, "sendAccountReAuthNotification: account: ${account.name}")

    registerAccountNotificationChannel()

    val intent = Intent(this, LoginActivity::class.java).apply {
        putExtra(LoginActivity.EXTRA_RE_AUTH_ACCOUNT, account)
    }.let {
        PendingIntentCompat.getActivity(
            this, account.hashCode(), it, PendingIntent.FLAG_CANCEL_CURRENT, false
        )
    }

    val notification: Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manage_accounts)
            .setSound(null)
            .setContentTitle(getString(R.string.auth_action_reauth_notification_title))
            .setContentText(account.name)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(this).notify(account.hashCode(), notification)
    }
}

@RequiresApi(21)
fun Context.sendAccountActionNotification(account: Account, action: UserSatisfyRequirements) {

    registerAccountNotificationChannel()

    val intent: PendingIntent? = AccountActionActivity.createIntent(this, account, action).let {
        PendingIntentCompat.getActivity(
            this,
            account.hashCode(),
            it,
            PendingIntent.FLAG_CANCEL_CURRENT,
            false
        )
    }

    val notification: Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manage_accounts)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.auth_action_notification_title))
            .setContentText(getString(R.string.auth_action_notification_content))
            .setSubText(account.name)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

    postAccountActionNotification(account, notification)
}

/** Posts [account]'s "Account action required" under [ACCOUNT_ACTION_TAG], which [clearAccountActionNotification] clears. */
internal fun Context.postAccountActionNotification(account: Account, notification: Notification) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(this).notify(ACCOUNT_ACTION_TAG, account.hashCode(), notification)
    }
}

/**
 * Clears [account]'s "Account action required" once Google issued it a token: whatever it asked for (a screen lock,
 * check-in, push) is done. Without this the notice stays until tapped, telling the owner a working account still needs
 * setup. Leaves the account's re-auth and unsupported-management notices alone.
 *
 * @return whether such a notice was showing and is now cleared
 */
fun Context.clearAccountActionNotification(account: Account): Boolean {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
    if (SDK_INT >= 23 && manager.activeNotifications.none { it.id == account.hashCode() && it.tag == ACCOUNT_ACTION_TAG }) {
        return false
    }
    manager.cancel(ACCOUNT_ACTION_TAG, account.hashCode())
    return SDK_INT >= 23
}

/** Explains, once, that the account's organization requires management microG cannot provide. */
@RequiresApi(21)
fun Context.sendAccountUnsupportedNotification(account: Account) {
    registerAccountNotificationChannel()

    val intent = PendingIntentCompat.getActivity(
        this, account.hashCode(), Intent(this, MainSettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT, false
    )
    val text = getString(R.string.auth_action_unsupported_notification_content, account.name)
    val notification: Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_manage_accounts)
            .setSound(null)
            .setContentTitle(getString(R.string.auth_action_unsupported_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(this).notify(account.hashCode(), notification)
    }
}

fun Context.registerAccountNotificationChannel() {
    if (SDK_INT >= 26) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auth_action_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.auth_action_notification_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

fun Context.cancelAccountNotificationChannel(account: Account) {
    NotificationManagerCompat.from(this).cancel(account.hashCode())
    NotificationManagerCompat.from(this).cancel(ACCOUNT_ACTION_TAG, account.hashCode())
}