/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import android.Manifest
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.R
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthPrefs
import org.microg.gms.auth.authzen.ui.AuthZenActivity
import org.microg.gms.cryptauth.getCryptAuthUserKey
import org.microg.gms.gcm.GcmConstants
import java.util.concurrent.Executors

private const val CHANNEL_ID = "cyclon_authzen"
private const val NOTIFICATION_TAG = "authzen"

internal const val EXTRA_AUTHZEN_HANDLE = "org.microg.gms.auth.authzen.HANDLE"

/** A local handle as made by [handle]; anything else in an Intent is ignored. */
internal fun isAuthZenHandle(value: String?): Boolean = value != null && value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal val authZenDiagnostics = AuthZenDiagnostics()
private val authZenAlerts = AlertLimiter()
private val authZenExecutor = Executors.newSingleThreadExecutor()

/** Whether a GmsCore push message is a SecureGcm candidate; GNOTS tickles never are. Cheap, for the main thread. */
internal fun Intent.isSecureGcmCandidate(): Boolean =
    getStringExtra(GcmConstants.EXTRA_GCM_BODY) != "notifications" && hasExtra(SecureGcm.MESSAGE_KEY)

/** Hands a SecureGcm message to the native route off the main thread; [done] runs afterwards. */
internal fun Context.dispatchAuthZenMessage(intent: Intent, done: () -> Unit) {
    val context = applicationContext
    val from = intent.getStringExtra(GcmConstants.EXTRA_FROM)
    val body = intent.getStringExtra(GcmConstants.EXTRA_GCM_BODY)
    val payload = intent.getStringExtra(SecureGcm.MESSAGE_KEY)
    authZenExecutor.execute {
        try {
            context.authZenIngress().onMessage(from, body, payload)
        } catch (e: Exception) {
            authZenDiagnostics.event(AuthZenEvent.CRYPTO_FAILED, null, "internal" to true)
        } finally {
            done()
        }
    }
}

internal fun Context.authZenNow(): AuthZenNow {
    val wall = System.currentTimeMillis()
    val elapsed = SystemClock.elapsedRealtime()
    val bootCount = if (SDK_INT >= 24) Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) else null
    return AuthZenNow(wall, elapsed, authZenBootId(bootCount, wall, elapsed))
}

internal fun Context.authZenRepository() = AuthZenRepository(authZenStorage(), CryptAuthFileLock(this))
internal fun Context.authZenKeys() = AuthZenKeyRegistry(authZenStorage(), CryptAuthFileLock(this))

private fun Context.googleAccounts(): List<String> =
    AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).map { it.name }

internal fun Context.authZenBlocker(): AuthZenBlocker? {
    if (!AuthZenFeature.enabled) return AuthZenBlocker.FEATURE_OFF
    val keys = authZenKeys()
    return authZenBlocker(AuthZenReadinessInput(
        featureEnabled = true,
        privateStorage = SDK_INT >= 21,
        ownerOptedIn = AuthPrefs.shouldReceiveTwoStepVerification(this),
        verifiedProfiles = AuthZenProfiles.verified.size,
        accountsWithAuthZenKey = googleAccounts().count { keys.keys(it).active != null },
        secureLock = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).let { if (SDK_INT >= 23) it.isDeviceSecure else it.isKeyguardSecure },
        notificationsAllowed = authZenNotificationsAllowed(),
    ))
}

private fun Context.authZenIngress() = AuthZenIngress(
    diagnostics = authZenDiagnostics,
    blocker = { authZenBlocker() },
    labCapture = { AuthZenFeature.labCaptureActive() },
    accounts = { googleAccounts() },
    keys = authZenKeys(),
    decoder = AuthZenDecoder(),
    repository = authZenRepository(),
    notifier = AuthZenNotifier(this),
    alerts = authZenAlerts,
    now = { authZenNow() },
)

internal fun Context.authZenSender() = AuthZenSender(
    repository = authZenRepository(),
    keys = authZenKeys(),
    profiles = AuthZenProfiles.verified,
    // No network client while no profile is verified: nothing can reach Google from this route.
    api = if (AuthZenFeature.enabled && AuthZenProfiles.verified.isNotEmpty()) AuthZenApi(HttpAuthZenTransport(), AccountManagerTokenProvider(this)) else null,
    accountPresent = { account -> googleAccounts().contains(account) },
    optedIn = { AuthZenFeature.enabled && AuthPrefs.shouldReceiveTwoStepVerification(this) },
    userKey = { account -> getCryptAuthUserKey(account)?.let { it.keyPair to it.keyMaterial } },
    diagnostics = authZenDiagnostics,
    now = { authZenNow() },
)

internal fun Context.authZenConsent() = AuthZenConsent(authZenRepository(), authZenSender(), authZenDiagnostics, { authZenNow() })

internal fun Context.authZenNotificationsAllowed(): Boolean {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    if (SDK_INT < 26) return true
    val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID) ?: return true
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}

/**
 * A high-importance notification that says only that a Google sign-in request is waiting, on the lock screen and
 * off it. It has no action buttons; its only Intent opens [AuthZenActivity] with the opaque local handle.
 */
internal class AuthZenNotifier(private val context: Context) : AuthZenIngress.Notifier {

    override fun show(record: AuthZenRecord): Boolean {
        registerChannel()
        if (!context.authZenNotificationsAllowed()) return false
        val intent = Intent(context, AuthZenActivity::class.java).apply {
            putExtra(EXTRA_AUTHZEN_HANDLE, record.handle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context, record.handle.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.cyclon_authzen_notification_title))
            .setContentText(context.getString(R.string.cyclon_authzen_notification_public_text))
            .setSmallIcon(R.drawable.ic_google_logo)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.cyclon_authzen_notification_title))
            .setContentText(context.getString(R.string.cyclon_authzen_notification_text))
            .setSmallIcon(R.drawable.ic_google_logo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter((record.serverExpiresAtMillis - System.currentTimeMillis()).coerceIn(1_000L, AuthZenDecoder.MAX_LIFETIME_MILLIS))
            .setContentIntent(pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, record.handle.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    override fun cancel(handle: String) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, handle.hashCode())

    private fun registerChannel() {
        if (SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.cyclon_authzen_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.cyclon_authzen_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
