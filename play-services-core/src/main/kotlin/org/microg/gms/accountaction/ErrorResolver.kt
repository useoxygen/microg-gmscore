// Modified by Cyclon in 2026: bounded CryptAuth screen lock proof, clearer diagnosis, unsupported-management notice.
package org.microg.gms.accountaction

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.microg.gms.auth.AuthPrefs
import org.microg.gms.auth.login.LoginActivity
import org.microg.gms.common.Constants
import org.microg.gms.cryptauth.CryptAuthHttpException
import org.microg.gms.cryptauth.isLockscreenConfigured
import org.microg.gms.cryptauth.sendDeviceScreenlockState
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.gcm.GcmPrefs
import org.microg.gms.settings.SettingsContract
import java.io.IOException


/**
 * High-level resolution: tell server that user has configured a lock screen
 */
const val DEVICE_MANAGEMENT_SCREENLOCK_REQUIRED = "DeviceManagementScreenlockRequired"

/**
 * Indicates that the user is using an enterprise account that is set up to use Advanced
 * device management features, for which it is required to install a device manager.
 * This is not supported by microG.
 */
const val DEVICE_MANAGEMENT_REQUIRED = "DeviceManagementRequired"

/**
 * Indicates that the user is using an enterprise account that is set up to use Advanced
 * device management features, for which it is required to install a device manager,
 * and that the device also needs manual admin approval.
 * This is not supported by microG.
 */
const val DEVICE_MANAGEMENT_ADMIN_PENDING_APPROVAL = "DeviceManagementAdminPendingApproval"

/**
 * Indicates that the token stored on the device is no longer valid.
 */
const val BAD_AUTHENTICATION = "BadAuthentication"

const val SERVER_ERROR = "Error 500"

const val TAG = "GmsAccountErrorResolve"

private val cryptAuthAttempts = CryptAuthAttempts()

/** Accounts already told in this process that their management mode is unsupported. */
private val unsupportedNotified = HashSet<String>()

/**
 * @return `null` if it is unknown how to resolve the problem, an
 * appropriate `Resolution` otherwise
 */
fun Context.resolveAuthErrorMessage(s: String): Resolution? = if (s.startsWith("Error=")) {
    resolveAuthErrorMessage(s.drop("Error=".length))
} else if (s.contains(SERVER_ERROR)) {
    Reauthenticate
} else when (s) {
    DEVICE_MANAGEMENT_SCREENLOCK_REQUIRED -> listOf(
        Requirement.ENABLE_CHECKIN,
        Requirement.AUTH_WITH_DEVICE_REGISTRATION,
        Requirement.ENABLE_GCM,
        Requirement.ALLOW_MICROG_GCM,
        Requirement.ENABLE_LOCKSCREEN
    )
        .associateWith { checkRequirementSatisfied(it) }
        .filterValues { satisfied -> !satisfied }.let {
            if (it.isEmpty()) {
                // all requirements are satisfied, crypt auth sync keys can be run
                CryptAuthSyncKeys
            } else {
                // prompt user to satisfy missing requirements
                UserSatisfyRequirements(it.keys)
            }
        }

    DEVICE_MANAGEMENT_ADMIN_PENDING_APPROVAL, DEVICE_MANAGEMENT_REQUIRED ->
        NoResolution(NoResolutionReason.ADVANCED_DEVICE_MANAGEMENT_NOT_SUPPORTED)

    BAD_AUTHENTICATION -> Reauthenticate

    else -> null
}.also { Log.i(TAG, "Error was: $s. Diagnosis: ${it ?: "unknown, not resolved"}.") }

fun Context.checkRequirementSatisfied(requirement: Requirement): Boolean = when (requirement) {
    Requirement.ENABLE_CHECKIN -> isCheckinEnabled()
    Requirement.AUTH_WITH_DEVICE_REGISTRATION -> AuthPrefs.shouldIncludeAndroidId(this)
    Requirement.ENABLE_GCM -> isGcmEnabled()
    Requirement.ALLOW_MICROG_GCM -> isMicrogAppGcmAllowed()
    Requirement.ENABLE_LOCKSCREEN -> isLockscreenConfigured()
}

fun Context.isCheckinEnabled(): Boolean {
    val settingsProjection = arrayOf(
        SettingsContract.CheckIn.ENABLED,
        SettingsContract.CheckIn.LAST_CHECK_IN
    )
    return SettingsContract.getSettings(this, SettingsContract.CheckIn.getContentUri(this), settingsProjection) { cursor ->
        val checkInEnabled = cursor.getInt(0) != 0
        val lastCheckIn = cursor.getLong(1)

        // user is also asked to enable checkin if there had never been a successful checkin (network errors?)
        lastCheckIn > 0 && checkInEnabled
    }
}

fun Context.isGcmEnabled(): Boolean = GcmPrefs.get(this).isEnabled

fun Context.isMicrogAppGcmAllowed(): Boolean {
    val gcmPrefs = GcmPrefs.get(this)
    val gcmDatabaseEntry = GcmDatabase(this).use {
        it.getApp(Constants.GMS_PACKAGE_NAME)
    }
    return !(gcmDatabaseEntry != null &&
            !gcmDatabaseEntry.allowRegister ||
            gcmDatabaseEntry == null &&
            gcmPrefs.confirmNewApps)

}

fun <T> Resolution.initiateFromBackgroundBlocking(context: Context, account: Account, retryFunction: RetryFunction<T>): T? {
    when (this) {
        CryptAuthSyncKeys -> return resolveWithCryptAuth(context, account, retryFunction)
        is NoResolution -> {
            context.reportNoResolution(account, this)
            return null
        }
        is UserSatisfyRequirements -> {
            Log.w(TAG, "User intervention required! You need to ${actions.joinToString(", ")}.")
            if (SDK_INT >= 21) {
                context.sendAccountActionNotification(account, this)
            }
            return null
        }
        Reauthenticate -> {
            Log.w(TAG, "Your account credentials have expired! Please remove the account, then sign in again.")
            if (SDK_INT >= 21) {
                context.sendAccountReAuthNotification(account)
            }
            return null
        }
    }
}

fun <T> Resolution.initiateFromForegroundBlocking(context: Context, account: Account, retryFunction: RetryFunction<T>): T? {
    when (this) {
        CryptAuthSyncKeys -> return resolveWithCryptAuth(context, account, retryFunction)
        is NoResolution -> {
            context.reportNoResolution(account, this)
            return null
        }
        is UserSatisfyRequirements -> {
            Log.w(TAG, "User intervention required! You need to ${actions.joinToString(", ")}.")
            if (SDK_INT >= 21) {
                AccountActionActivity.createIntent(context, account, this).let {
                    context.startActivity(it)
                }
            }
            return null
        }
        Reauthenticate -> {
            Log.w(TAG, "Your account credentials have expired! Please remove the account, then sign in again.")
            Intent(context, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_RE_AUTH_ACCOUNT, account)
            }.let {
                context.startActivity(it)
            }
            return null
        }
    }
}

/**
 * Proves the secure screen lock to CryptAuth within [CryptAuthAttempts]' bounds, then retries the
 * token request once.
 *
 * @return `null` without retrying when the proof is skipped or fails, so callers report the
 * original error instead of a CryptAuth one
 */
private fun <T> resolveWithCryptAuth(context: Context, account: Account, retryFunction: RetryFunction<T>): T? {
    when (val decision = cryptAuthAttempts.decide(account.name)) {
        is CryptAuthAttempts.Decision.Wait -> {
            val minutes = ((decision.untilMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
            val cause = when (decision.reason) {
                CryptAuthAttempts.WaitReason.RATE_LIMITED -> "CryptAuth rate limited this account"
                CryptAuthAttempts.WaitReason.RECENT_FAILURE -> "the last attempt failed"
                CryptAuthAttempts.WaitReason.ATTEMPTS_EXHAUSTED -> "recent attempts did not unlock this account"
            }
            Log.w(TAG, "Not proving the screen lock to Google: $cause. Next try in about $minutes min.")
            return null
        }
        CryptAuthAttempts.Decision.InProgress -> {
            Log.i(TAG, "Screen lock proof for this account is already running; not starting another.")
            return null
        }
        CryptAuthAttempts.Decision.RetryTokenOnly -> {
            Log.i(TAG, "Screen lock was just proven for this account; retrying the token only.")
            return retryFunction.run()
        }
        CryptAuthAttempts.Decision.Run -> Unit
    }
    Log.i(TAG, "Resolving account error by proving the secure screen lock to Google (CryptAuth syncKeys/enrollKeys).")
    var outcome = CryptAuthAttempts.Outcome.FAILED
    try {
        outcome = context.proveScreenlock(account)
    } finally {
        cryptAuthAttempts.record(account.name, outcome)
    }
    return if (outcome == CryptAuthAttempts.Outcome.SUCCEEDED) retryFunction.run() else null
}

private fun Context.proveScreenlock(account: Account): CryptAuthAttempts.Outcome = try {
    if (runBlocking { sendDeviceScreenlockState(account) }) {
        CryptAuthAttempts.Outcome.SUCCEEDED
    } else {
        Log.w(TAG, "Screen lock proof did not complete; see CryptAuthFlow logs.")
        CryptAuthAttempts.Outcome.FAILED
    }
} catch (e: CryptAuthHttpException) {
    Log.w(TAG, "CryptAuth refused the screen lock proof with HTTP ${e.code}.")
    if (e.code == 429) CryptAuthAttempts.Outcome.RATE_LIMITED else CryptAuthAttempts.Outcome.FAILED
} catch (e: Exception) {
    Log.w(TAG, "Screen lock proof failed: ${e.javaClass.simpleName}: ${e.message}")
    CryptAuthAttempts.Outcome.FAILED
}

private fun Context.reportNoResolution(account: Account, resolution: NoResolution) {
    Log.w(TAG, "This account cannot be used with microG due to ${resolution.reason}: its organization requires " +
            "advanced mobile management or admin approval, which microG does not support.")
    val first = synchronized(unsupportedNotified) { unsupportedNotified.add(account.name) }
    if (first && SDK_INT >= 21) sendAccountUnsupportedNotification(account)
}

interface RetryFunction<T> {
    @Throws(IOException::class)
    fun run(): T
}