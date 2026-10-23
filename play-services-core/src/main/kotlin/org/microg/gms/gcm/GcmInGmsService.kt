// Modified by Cyclon in 2026: deliver GmsCore messages before registration upkeep, allow GmsCore's own registration
// while the owner's two-step setting is on, prompt registrations only while it is on, redacted logs and two-step
// readiness diagnostics.
/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.gcm

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import androidx.core.app.PendingIntentCompat
import androidx.legacy.content.WakefulBroadcastReceiver
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.AuthPrefs
import org.microg.gms.auth.gcm.SelfRegistration
import org.microg.gms.auth.gcm.TwoStepReadiness
import org.microg.gms.auth.gcm.admitTwoStepMessage
import org.microg.gms.auth.gcm.mayGrantOwnPushPermission
import org.microg.gms.auth.gcm.twoStepChannelAllowed
import org.microg.gms.auth.gcm.twoStepDiagnostics
import org.microg.gms.auth.gcm.twoStepNotificationsAllowed
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.common.Constants
import org.microg.gms.common.ForegroundServiceContext
import org.microg.gms.gcm.registeration.ChimeGmsRegistrationHelper
import org.microg.gms.profile.ProfileManager

private const val TAG = "GcmInGmsService"

private const val KEY_GCM_REG_SENDER = "sender"
private const val KEY_GCM_REG_TIME = "reg_time"
private const val KEY_GCM_REG_ACCOUNT_LIST = "accountList"

private const val GMS_GCM_REGISTER_SCOPE = "GCM"
private const val GMS_GCM_REGISTER_SENDER = "745476177629"
private const val GMS_GCM_REGISTER_SUBTYPE = "745476177629"
private const val GMS_GCM_REGISTER_SUBSCRIPTION = "745476177629"
private const val GCM_GROUP_SENDER = "google.com"
private const val GCM_GMS_REG_REFRESH_S = 604800L
private const val GMS_GCM_OAUTH_SERVICE = "oauth2:https://www.googleapis.com/auth/gcm"

class GcmInGmsService : LifecycleService() {
    private var sp: SharedPreferences? = null
    private var accountManager: AccountManager? = null
    private val chimeGmsRegistrationHelper by lazy { ChimeGmsRegistrationHelper(this) }

    override fun onCreate() {
        super.onCreate()
        ProfileManager.ensureInitialized(this)
        sp = getSharedPreferences("com.google.android.gcm", MODE_PRIVATE) ?: throw RuntimeException("sp get error")
        accountManager = getSystemService(ACCOUNT_SERVICE) as AccountManager? ?: throw RuntimeException("accountManager is null")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            ForegroundServiceContext.completeForegroundService(this, intent, TAG)
            Log.d(TAG, "onStartCommand: ${intent.action}")
            lifecycleScope.launchWhenStarted {
                // A received message has already arrived over the connection; it must not be lost to a reconnect.
                if (intent.action == GcmConstants.ACTION_C2DM_RECEIVE || checkGcmStatus()) {
                    handleIntent(intent)
                } else {
                    val intent = Intent(ACTION_GCM_RECONNECT).apply {
                        setPackage(Constants.GMS_PACKAGE_NAME)
                    }
                    sendBroadcast(intent)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun checkGcmStatus(): Boolean {
        if (McsService.isConnected(this)) {
            Log.d(TAG, "checkGcmStatus: gcm isConnected")
            return true
        }
        Log.d(TAG, "checkGcmStatus: gcm need reconnect")
        return false
    }

    private suspend fun handleIntent(intent: Intent) {
        val action = intent.action
        if (action == GcmConstants.ACTION_C2DM_RECEIVE) {
            // Deliver first: the message was sent to the current registration, whatever upkeep is due.
            val callerIntent = Intent(ACTION_GCM_MESSAGE_RECEIVE)
            callerIntent.setPackage(Constants.GMS_PACKAGE_NAME)
            callerIntent.putExtras(intent)
            sendOrderedBroadcast(callerIntent, null)
            if (checkGmsGcmStatus()) {
                runCatching { registerGcmInGms(this, Intent(ACTION_GCM_REGISTER_ALL_ACCOUNTS)) }.onFailure {
                    Log.w(TAG, "handleIntent: registerGcmInGms error: ${it.javaClass.simpleName}")
                }
            }
            return
        }
        if (checkGmsGcmStatus()) {
            Log.d(TAG, "handleIntent: checkGmsGcmStatus -> reset")
            runCatching { registerGcmInGms(this, intent) }.onFailure {
                Log.w(TAG, "handleIntent: registerGcmInGms error: ${it.javaClass.simpleName}")
            }
            logTwoStepReadiness()
            return
        }
        Log.d(TAG, "handleIntent: action: $action")
        when (action) {
            ACTION_GCM_REGISTER_ALL_ACCOUNTS -> updateLocalAccountGroups(explicit = true)
            ACTION_GCM_CONNECTED -> updateLocalAccountGroups(explicit = false)
            ACTION_GCM_REGISTER_ACCOUNT -> {
                val accountName = intent.getStringExtra(KEY_GCM_REGISTER_ACCOUNT_NAME) ?: return
                val account = accountManager?.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)?.find { it.name == accountName } ?: return
                updateLocalAccountGroups(account, explicit = true)
            }
        }
        logTwoStepReadiness()
    }

    /** Logs whether Google's two-step prompts can reach this phone; booleans and counts only. */
    private suspend fun logTwoStepReadiness() = withContext(Dispatchers.IO) {
        if (!AuthPrefs.shouldReceiveTwoStepVerification(this@GcmInGmsService)) return@withContext
        val database = GcmDatabase(this@GcmInGmsService)
        val app = try { database.getApp(Constants.GMS_PACKAGE_NAME) } finally { database.close() }
        TwoStepReadiness(
            prefOn = true,
            gcmEnabled = GcmPrefs.get(this@GcmInGmsService).isEnabled,
            selfRegistration = when {
                app == null -> SelfRegistration.NOT_YET_KNOWN
                app.allowRegister -> SelfRegistration.ALLOWED
                else -> SelfRegistration.DENIED
            },
            gmsRegistered = !checkGmsGcmStatus(),
            accounts = accountManager?.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)?.size ?: 0,
            chimeRegisteredAccounts = chimeGmsRegistrationHelper.registeredAccountCount(),
            notificationsAllowed = twoStepNotificationsAllowed(),
            channelAllowed = twoStepChannelAllowed(),
        ).log(twoStepDiagnostics)
    }

    private suspend fun updateGroupsWithAccount(account: Account, regId: String) {
        val authManager = AuthManager(this, account.name, Constants.GMS_PACKAGE_NAME, GMS_GCM_OAUTH_SERVICE).apply {
            setItCaveatTypes("2")
        }
        val authsToken = runCatching { withContext(Dispatchers.IO) { authManager.requestAuth(true) }.parseAuthsToken() }.getOrNull() ?: return
        val extras = Bundle().apply {
            putString(GcmConstants.EXTRA_ACCOUNT_NAME, account.name)
            putString(GcmConstants.EXTRA_REG_ID, regId)
            putString(GcmConstants.EXTRA_AUTHS_TOKEN, authsToken)
            putString(GcmConstants.EXTRA_SEND_TO, GCM_GROUP_SENDER)
            putString(GcmConstants.EXTRA_SEND_FROM, GCM_GROUP_SENDER)
            putString(GcmConstants.EXTRA_MESSAGE_ID, "${System.currentTimeMillis() / 1000}-0")
        }
        val intent = Intent(GcmConstants.ACTION_GCM_SEND).apply {
            setPackage(Constants.GMS_PACKAGE_NAME)
            putExtras(extras)
            putExtra(GcmConstants.EXTRA_APP, Intent().apply { setPackage(Constants.GMS_PACKAGE_NAME) }.let { PendingIntentCompat.getBroadcast(this@GcmInGmsService, 0, it, 0, false) })
        }.also {
            it.putExtra(GcmConstants.EXTRA_MESSENGER, Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (Binder.getCallingUid() == Process.myUid()) {
                        val history = sp?.getString(KEY_GCM_REG_ACCOUNT_LIST, "")
                        if (history?.contains(account.name) == true) return
                        val saveStr = if (history.isNullOrEmpty()) account.name else "${account.name}/$history"
                        sp?.edit()?.putString(KEY_GCM_REG_ACCOUNT_LIST, saveStr)?.apply()
                        Log.d(TAG, "updateGroupsWithAccount: account group recorded")
                    }
                }
            }))
        }
        sendOrderedBroadcast(intent, null)
    }

    private suspend fun updateLocalAccountGroups(account: Account? = null, explicit: Boolean) {
        val regId = sp?.getString(KEY_GCM_REG_ID, null) ?: return
        // Accounts are registered for prompt notifications only while the owner wants prompts. Turning the setting off
        // stops new registrations; existing ones are not withdrawn (see docs/google-prompt.md), and prompts that still
        // arrive are dropped at the receiver.
        val accounts = if (AuthPrefs.shouldReceiveTwoStepVerification(this)) {
            chimeGmsRegistrationHelper.handleRegistration(regId, explicit = explicit)
        } else emptyList()
        if (accounts.isNotEmpty()) {
            accounts.forEach { updateGroupsWithAccount(it, regId) }
            return
        }
        val localGoogleAccounts = accountManager?.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE) ?: return
        val accountList = sp?.getString(KEY_GCM_REG_ACCOUNT_LIST, null)
        if (account != null && accountList?.contains(account.name) != true) {
            updateGroupsWithAccount(account, regId)
            return
        }
        val needRegisterAccounts = if (accountList == null) localGoogleAccounts.toList() else localGoogleAccounts.filter { !accountList.contains(it.name) }
        if (needRegisterAccounts.isEmpty()) return
        Log.d(TAG, "updateLocalAccountGroups: ${needRegisterAccounts.size} account group(s) to update")
        needRegisterAccounts.forEach { updateGroupsWithAccount(it, regId) }
    }

    /**
     * Registers GmsCore for Google's notification sender, then broadcasts [followUp] so accounts get registered for
     * the new id. While the two-step setting is on, GmsCore's own push permission is granted here when microG has not
     * asked yet (a permission dialog cannot open from this background service; see [mayGrantOwnPushPermission]); an
     * explicit grant or denial in microG's push settings is kept, and with the setting off microG's usual check
     * applies. [followUp] is only sent when the registration is complete, so a failed write cannot loop.
     */
    private suspend fun registerGcmInGms(context: Context, followUp: Intent) {
        Log.i(TAG, "Registering GMS $GMS_GCM_REGISTER_SENDER")
        val regId = withContext(Dispatchers.IO) {
            val request = RegisterRequest().build(context)
                .checkin(LastCheckinInfo.read(context))
                .app(Constants.GMS_PACKAGE_NAME, Constants.GMS_PACKAGE_SIGNATURE_SHA1, BuildConfig.VERSION_CODE)
                .sender(GMS_GCM_REGISTER_SENDER)
                .extraParam("subscription", GMS_GCM_REGISTER_SUBSCRIPTION)
                .extraParam("X-subscription", GMS_GCM_REGISTER_SUBSCRIPTION)
                .extraParam("subtype", GMS_GCM_REGISTER_SUBTYPE)
                .extraParam("X-subtype", GMS_GCM_REGISTER_SUBTYPE)
                .extraParam("scope", GMS_GCM_REGISTER_SCOPE)
            val gcmDatabase = GcmDatabase(context)
            try {
                grantOwnPushPermissionIfAbsent(
                    gcmDatabase,
                    packageName = request.app,
                    gmsPackageName = Constants.GMS_PACKAGE_NAME,
                    twoStepOn = AuthPrefs.shouldReceiveTwoStepVerification(context),
                    gcmEnabled = GcmPrefs.get(context).isEnabled,
                )
                ensureAppRegistrationAllowed(context, gcmDatabase, request.app)
                completeRegisterRequest(context, gcmDatabase, request).getString(GcmConstants.EXTRA_REGISTRATION_ID)
            } finally {
                gcmDatabase.close()
            }
        }
        if (regId == null) {
            Log.w(TAG, "registerGcmInGms: no registration id")
            return
        }
        val sharedPreferencesEditor = sp?.edit()
        sharedPreferencesEditor?.putLong(KEY_GCM_ANDROID_ID, LastCheckinInfo.read(context).androidId)
        sharedPreferencesEditor?.putString(KEY_GCM_REG_ID, regId)
        sharedPreferencesEditor?.putString(KEY_GCM_REG_SENDER, GMS_GCM_REGISTER_SENDER)
        sharedPreferencesEditor?.putLong(KEY_GCM_REG_TIME, System.currentTimeMillis())
        sharedPreferencesEditor?.remove(KEY_GCM_REG_ACCOUNT_LIST)
        chimeGmsRegistrationHelper.resetAllData()
        if (sharedPreferencesEditor?.commit() != true || checkGmsGcmStatus()) {
            Log.w(TAG, "Failed to write GMS registration")
        } else {
            Log.d(TAG, "registerGcmInGms: sendBroadcast: ${followUp.action}")
            Intent(followUp.action).apply {
                setPackage(Constants.GMS_PACKAGE_NAME)
                putExtras(followUp)
            }.let { sendBroadcast(it) }
        }
    }

    private fun checkGmsGcmStatus(): Boolean {
        val targetId = LastCheckinInfo.read(this).androidId
        val regSender = sp?.getString(KEY_GCM_REG_SENDER, null)
        val regId = sp?.getString(KEY_GCM_REG_ID, null)
        val androidId = sp?.getLong(KEY_GCM_ANDROID_ID, 0)
        val regTime = sp?.getLong(KEY_GCM_REG_TIME, 0) ?: 0L
        return targetId != androidId || regSender == null || regId == null || regTime + GCM_GMS_REG_REFRESH_S * 1000 < System.currentTimeMillis()
    }
}

/**
 * GmsCore's push self-grant ([mayGrantOwnPushPermission]). The lookup is only a fast path: the grant itself is an
 * insert-only write, so an explicit grant or denial that microG's permission dialog or settings commit after the lookup
 * is never overwritten. The caller still checks the resulting permission before registering.
 *
 * @return whether the grant was recorded
 */
internal fun grantOwnPushPermissionIfAbsent(database: GcmDatabase, packageName: String, gmsPackageName: String, twoStepOn: Boolean, gcmEnabled: Boolean): Boolean {
    val known = database.getApp(packageName) != null
    if (!mayGrantOwnPushPermission(packageName, gmsPackageName, twoStepOn, gcmEnabled, knownToMicroG = known)) return false
    return database.noteAppKnownIfAbsent(packageName, true)
}

class GcmRegistrationReceiver : WakefulBroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldReceiveTwoStepVerification = AuthPrefs.shouldReceiveTwoStepVerification(context)
        val allowedFindDevicesRequest = AuthPrefs.allowedFindDevices(context)
        if (!shouldReceiveTwoStepVerification && !allowedFindDevicesRequest) {
            if (GcmConstants.ACTION_C2DM_RECEIVE == intent.action) {
                admitTwoStepMessage(false, intent.getStringExtra(GcmConstants.EXTRA_GCM_BODY), twoStepDiagnostics)
            }
            return
        }
        val callIntent = Intent(context, GcmInGmsService::class.java)
        callIntent.action = intent.action
        if (ACTION_GCM_REGISTER_ACCOUNT == intent.action || GcmConstants.ACTION_C2DM_RECEIVE == intent.action) {
            intent.extras?.let { callIntent.putExtras(it) }
        }
        ForegroundServiceContext(context).startService(callIntent)
    }
}
