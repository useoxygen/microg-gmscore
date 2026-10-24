// Modified by Cyclon in 2026: redacted route diagnostics, tap-to-open prompt notification instead of a background activity start,
// SecureGcm messages handed to the native AuthZen route, Google Play services' read states, expiry and remote dismissal.
/**
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.gcm

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.legacy.content.WakefulBroadcastReceiver
import com.google.android.gms.R
import org.microg.gms.accountsettings.ui.EXTRA_ACCOUNT_NAME
import org.microg.gms.accountsettings.ui.KEY_NOTIFICATION_ID
import org.microg.gms.accountsettings.ui.MainActivity
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthPrefs
import org.microg.gms.auth.authzen.dispatchAuthZenMessage
import org.microg.gms.auth.authzen.isSecureGcmCandidate
import org.microg.gms.common.Constants
import org.microg.gms.common.ForegroundServiceContext
import org.microg.gms.gcm.AuthHeaderInterceptor
import org.microg.gms.gcm.DEFAULT_FLAGS
import org.microg.gms.gcm.DeviceInfo
import org.microg.gms.gcm.EXTRA_NOTIFICATION_ACCOUNT
import org.microg.gms.gcm.FetchByIdentifierRequest
import org.microg.gms.gcm.GMS_NOTS_BASE_URL
import org.microg.gms.gcm.GMS_NOTS_OAUTH_SERVICE
import org.microg.gms.gcm.GcmConstants
import org.microg.gms.gcm.GmsConfig
import org.microg.gms.gcm.GmsGnotsSetReadStatesRequest
import org.microg.gms.gcm.GunsGmscoreApiServiceClient
import org.microg.gms.gcm.NotificationData
import org.microg.gms.gcm.NotificationIdentifier
import org.microg.gms.gcm.NotificationIdentifierList
import org.microg.gms.gcm.ReadStateItem
import org.microg.gms.gcm.ReadStateList
import org.microg.gms.gcm.ReceiverService
import org.microg.gms.gcm.createGrpcClient
import org.microg.gms.profile.Build.VERSION.SDK_INT
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Gms2FA"
private const val CHANNEL_ID = "gcm_notification"
private const val NOTIFICATION_TAG = "two_step_verification"

const val ACTION_GCM_NOTIFY_COMPLETE = "org.microg.gms.gcm.NOTIFY_COMPLETE"
/** Sent by a prompt notification's delete intent when the owner swipes it away. */
const val ACTION_TWO_STEP_DISMISSED = "org.microg.gms.gcm.TWO_STEP_DISMISSED"
/** The prompt's GNOTS identifier (Base64 of the `NotificationIdentifier` proto), for read states without a lookup. */
const val EXTRA_GNOTS_IDENTIFIER = "org.microg.gms.gcm.GNOTS_IDENTIFIER"
/** The prompt's GNOTS unique id in the notification's extras, so a remote dismissal finds it after a restart. */
private const val EXTRA_GNOTS_UNIQUE_ID = "org.microg.gms.gcm.GNOTS_UNIQUE_ID"

internal val twoStepDiagnostics = TwoStepDiagnostics { Log.i(TAG, it) }

class GcmReceiver : WakefulBroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SecureGcm (`P`) messages belong to the native AuthZen route, which is off by default and only logs them.
        if (intent.isSecureGcmCandidate()) {
            val pending = goAsync()
            context.dispatchAuthZenMessage(intent) { pending.finish() }
            return
        }
        val prefOn = AuthPrefs.shouldReceiveTwoStepVerification(context)
        if (!admitTwoStepMessage(prefOn, intent.getStringExtra(GcmConstants.EXTRA_GCM_BODY), twoStepDiagnostics)) return
        val callIntent = Intent(context, GcmReceiverService::class.java)
        callIntent.action = intent.action
        intent.extras?.let { callIntent.putExtras(it) }
        ForegroundServiceContext(context).startService(callIntent)
    }
}

class GcmReceiverService : ReceiverService(TAG) {

    companion object {
        private val notificationIdGenerator = AtomicInteger(0)
    }

    override fun onCreate() {
        super.onCreate()
        registerTwoStepNotificationChannel()
    }

    override fun allowed(): Boolean = AuthPrefs.shouldReceiveTwoStepVerification(this)

    override fun receiver(intent: Intent) {
        val accountManager = getSystemService(ACCOUNT_SERVICE) as AccountManager? ?: return run { Log.w(TAG, "receiver: accountManager is null") }
        // The owner opened a prompt (Google's page reports it) or swiped it away (the delete intent).
        val ownerAction = when (intent.action) {
            ACTION_GCM_NOTIFY_COMPLETE -> NOTIFICATION_STATUS_OPENED to TwoStepEvent.OPENED
            ACTION_TWO_STEP_DISMISSED -> NOTIFICATION_STATUS_DISMISSED to TwoStepEvent.DISMISSED
            else -> null
        }
        if (ownerAction != null) {
            val (status, event) = ownerAction
            val notificationId = intent.getIntExtra(KEY_NOTIFICATION_ID, -1)
            if (notificationId != -1) NotificationManagerCompat.from(this).cancel(NOTIFICATION_TAG, notificationId)
            val accountName = intent.getStringExtra(EXTRA_NOTIFICATION_ACCOUNT)
            val identifier = intent.getStringExtra(EXTRA_GNOTS_IDENTIFIER)?.let {
                runCatching { NotificationIdentifier.ADAPTER.decode(Base64.decode(it, DEFAULT_FLAGS)) }.getOrNull()
            }
            val account = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).find { it.name == accountName }
            if (account != null && identifier != null) {
                try {
                    sendReadState(accountManager, account, identifier, status)
                } catch (e: Exception) {
                    twoStepDiagnostics.event(TwoStepEvent.READ_STATE_FAILED, null, "status" to status)
                }
            }
            twoStepDiagnostics.event(event, null, "reported" to (account != null && identifier != null))
            return
        }
        TwoStepRoute<Account>(
            diagnostics = twoStepDiagnostics,
            enabled = { AuthPrefs.shouldReceiveTwoStepVerification(this) },
            accounts = {
                accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).map { it to accountManager.getUserData(it, AuthConstants.GOOGLE_USER_ID) }
            },
            fetch = { account, tickle ->
                val list = requestNotificationInfo(accountManager, account, tickle).notifications
                GnotsFetch(list?.notificationDataList.orEmpty(), list?.serverTime?.takeIf { it > 0 }?.div(1000))
            },
            setReadState = { account, notification, status -> notification.identifier?.let { sendReadState(accountManager, account, it, status) } },
            show = { account, notification, timeoutMillis -> showNotification(account, notification, timeoutMillis) },
            dismiss = { uniqueId -> cancelPrompts(uniqueId) },
            decodeBase64 = { Base64.decode(it, DEFAULT_FLAGS) },
        ).route(
            intent.getStringExtra(GcmConstants.EXTRA_GCM_BODY),
            intent.getStringExtra(GcmConstants.EXTRA_GMS_GNOTS_PAYLOAD),
            intent.getLongExtra(GcmConstants.EXTRA_SENT_TIME, 0L).takeIf { it > 0 },
        )
    }

    /**
     * Posts a high-importance notification that opens Google's confirmation page on tap. Nothing in it approves the
     * sign-in, and nothing is started in the background: Android blocks background activity starts, and the owner
     * should choose when to look. The owner's notification permission and channel settings are respected.
     */
    private fun showNotification(account: Account, notificationData: NotificationData, timeoutMillis: Long): ShowResult {
        val content = notificationData.content ?: return ShowResult.NOT_SHOWABLE
        val intentExtras = notificationData.intentActions?.primaryPayload?.extras ?: return ShowResult.NOT_SHOWABLE
        if (!twoStepNotificationsAllowed()) return ShowResult.NOTIFICATIONS_BLOCKED
        val notificationId = notificationIdGenerator.incrementAndGet()
        val identifier = notificationData.identifier?.let { Base64.encodeToString(it.encode(), DEFAULT_FLAGS) }
        val intent = Intent(this, MainActivity::class.java).apply {
            `package` = Constants.GMS_PACKAGE_NAME
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            intentExtras.forEach { putExtra(it.key, it.value_) }
            // The page opens for the account the notification was routed to, whatever the fetched extras name.
            putExtra(EXTRA_ACCOUNT_NAME, account.name)
            putExtra(KEY_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_GNOTS_IDENTIFIER, identifier)
        }
        val pendingIntent = PendingIntentCompat.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
        val dismissIntent = Intent(ACTION_TWO_STEP_DISMISSED).apply {
            setClass(this@GcmReceiverService, GcmReceiver::class.java)
            putExtra(KEY_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_ACCOUNT, account.name)
            putExtra(EXTRA_GNOTS_IDENTIFIER, identifier)
        }
        val deletePendingIntent = PendingIntentCompat.getBroadcast(this, notificationId, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT, false)
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cyclon_two_step_notification_public_title))
            .setContentText(getString(R.string.cyclon_two_step_notification_public_text))
            .setSmallIcon(R.drawable.ic_google_logo)
            .build()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.accountName)
            .setContentText(content.description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .addExtras(Bundle().apply { putString(EXTRA_GNOTS_UNIQUE_ID, notificationData.identifier?.uniqueId) })
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMillis)
            .setSmallIcon(R.drawable.ic_google_logo)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_TAG, notificationId, builder.build())
        } catch (e: SecurityException) {
            return ShowResult.NOTIFICATIONS_BLOCKED
        }
        return ShowResult.SHOWN
    }

    /** Takes down the shown prompts with this GNOTS unique id, found by their extras so it works after a restart. */
    private fun cancelPrompts(uniqueId: String): Int {
        if (SDK_INT < 23) return 0
        val manager = getSystemService(NotificationManager::class.java) ?: return 0
        val matches = manager.activeNotifications.filter {
            it.tag == NOTIFICATION_TAG && it.notification.extras.getString(EXTRA_GNOTS_UNIQUE_ID) == uniqueId
        }
        matches.forEach { manager.cancel(it.tag, it.id) }
        return matches.size
    }

    private fun getCurrentLanguageTag(): String {
        return runCatching {
            if (SDK_INT >= 24) {
                resources.configuration.locales[0].toLanguageTag()
            } else {
                val locale = resources.configuration.locale
                locale.language + (if (locale.country.isEmpty()) "" else "-" + locale.country)
            }
        }.getOrDefault(Locale.getDefault().language)
    }

    private fun getDensityQualifier(): DeviceInfo.DensityQualifier {
        val dpi = resources.displayMetrics.densityDpi
        return when {
            dpi >= DisplayMetrics.DENSITY_XXHIGH -> DeviceInfo.DensityQualifier.XXHDPI
            dpi >= DisplayMetrics.DENSITY_XHIGH -> DeviceInfo.DensityQualifier.XHDPI
            dpi >= DisplayMetrics.DENSITY_HIGH -> DeviceInfo.DensityQualifier.HDPI
            dpi >= DisplayMetrics.DENSITY_TV -> DeviceInfo.DensityQualifier.TVDPI
            dpi >= DisplayMetrics.DENSITY_MEDIUM -> DeviceInfo.DensityQualifier.MDPI
            else -> DeviceInfo.DensityQualifier.LDPI
        }
    }

    private fun requestNotificationInfo(accountManager: AccountManager, account: Account, notificationData: NotificationData) =
        getGunsApiServiceClient(account, accountManager).GmsGnotsFetchByIdentifier().executeBlocking(FetchByIdentifierRequest.Builder().apply {
            config(GmsConfig.Builder().apply {
                versionInfo(GmsConfig.GmsVersionInfo(Constants.GMS_VERSION_CODE))
            }.build())
            identifiers(NotificationIdentifierList.Builder().apply {
                deviceInfo(DeviceInfo.Builder().apply {
                    densityQualifier(getDensityQualifier())
                    localeTag(getCurrentLanguageTag())
                    sdkVersion(SDK_INT)
                    density(resources.displayMetrics.density)
                    timeZoneId(TimeZone.getDefault().id)
                }.build())
                notifications(notificationData.identifier?.let { listOf(it) } ?: emptyList())
            }.build())
        }.build())

    private fun sendReadState(accountManager: AccountManager, account: Account, identifier: NotificationIdentifier, readState: Int) {
        val readStateList = readStateItems(identifier, readState) ?: return
        getGunsApiServiceClient(account, accountManager).GmsGnotsSetReadStates().executeBlocking(
            GmsGnotsSetReadStatesRequest.Builder().apply {
                config = GmsConfig.Builder().apply {
                    versionInfo(GmsConfig.GmsVersionInfo(Constants.GMS_VERSION_CODE))
                }.build()
                readStates = ReadStateList.Builder().apply { items = readStateList }.build()
            }.build()
        )
    }

    private fun getGunsApiServiceClient(account: Account, accountManager: AccountManager): GunsGmscoreApiServiceClient {
        val oauthToken = accountManager.blockingGetAuthToken(account, GMS_NOTS_OAUTH_SERVICE, true)
        return createGrpcClient<GunsGmscoreApiServiceClient>(baseUrl = GMS_NOTS_BASE_URL, interceptor = AuthHeaderInterceptor(oauthToken))
    }
}

/**
 * The read-state item for [readState]: like Google Play services, one item naming the notification, without a state
 * string (Google adds one only for action buttons, which this route does not show). Null without an identifier.
 */
internal fun readStateItems(identifier: NotificationIdentifier?, readState: Int): List<ReadStateItem>? {
    if (identifier?.uniqueId.isNullOrEmpty()) return null
    return listOf(ReadStateItem.Builder().apply {
        this.notification = identifier
        this.status = readState
    }.build())
}

/** The channel for Google sign-in prompts; it keeps upstream's id so an owner's existing channel choices stay. */
internal fun Context.registerTwoStepNotificationChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.cyclon_two_step_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.cyclon_two_step_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

/** Whether a sign-in prompt notification can appear: app notifications allowed, permission held, channel not off. */
internal fun Context.twoStepNotificationsAllowed(): Boolean {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    return twoStepChannelAllowed() != false
}

/** Whether the prompt channel is on, or null when it does not exist yet (or before Android 8). */
internal fun Context.twoStepChannelAllowed(): Boolean? {
    if (Build.VERSION.SDK_INT < 26) return null
    val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID) ?: return null
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}
