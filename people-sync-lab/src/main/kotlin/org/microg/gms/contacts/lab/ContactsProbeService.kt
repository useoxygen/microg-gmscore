/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/** Background access check: returns a consent-needed receipt instead of opening the shared screen. */
class ContactsProbeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "contacts-access-test"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Contacts access test", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Contacts access test").setContentText("Checking Google access in the background")
            .setOngoing(true).build())
        val name = intent?.getStringExtra("accountName")?.takeIf { it.isNotBlank() }
        if (Build.TYPE !in listOf("userdebug", "eng") || name == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        worker.execute {
            val account = Account(name, "com.google")
            val receipt = AccessProbe.receipt(account).put("status", "Requesting background read-only authorization")
            AccessProbe.save(this, receipt)
            try {
                val result = AccountManager.get(this).getAuthToken(account,
                    "oauth2:https://www.googleapis.com/auth/contacts.readonly", null, false, null, null).result
                val token = result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                if (token == null) {
                    receipt.put("status", if (result.containsKey(AccountManager.KEY_INTENT))
                        "Google consent requires a brief foreground window" else "Google authorization did not return a token")
                    AccessProbe.save(this, receipt)
                } else {
                    check(result.getString(AccountManager.KEY_ACCOUNT_NAME) == account.name)
                    check(result.getString(AccountManager.KEY_ACCOUNT_TYPE) == account.type)
                    AccessProbe.verify(this, receipt, token)
                }
            } catch (e: Exception) {
                AccessProbe.errorCode(e)?.let { receipt.put("authReason", it) }
                receipt.put("exception", e.javaClass.simpleName)
                    .put("status", "Background authorization stopped (${e.javaClass.simpleName})")
                AccessProbe.save(this, receipt)
            } finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
