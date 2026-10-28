/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.app.Activity
import android.content.Intent
import android.os.*
import android.widget.*
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import java.io.File

/** Privileged setup-host handoff. Switch's copy entry consumes a retained session;
 * it cannot create one by calling tx13. This entry creates one through the same
 * legacy producer as target76, with no exported secret/session setter. */
class AndroidD2dBootstrapActivity : Activity() {
    private var bootstrap: LegacyTargetBootstrap?=null
    private var targetUid=-1
    private lateinit var sessions: AndroidSessionStore
    private lateinit var message: TextView
    private lateinit var proceed: Button
    private val handler=Handler(Looper.getMainLooper())
    private var canProceed=false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        message=TextView(this).apply { textSize=22f; text=getString(com.google.android.gms.R.string.android_d2d_prepare) }
        proceed=Button(this).apply { text=getString(com.google.android.gms.R.string.android_d2d_continue); isEnabled=false; setOnClickListener { continueCopy() } }
        // Cancel forgets a pairing this screen holds, so trying again pairs afresh instead of reusing it; never one
        // Switch is using for a copy right now.
        val cancel=Button(this).apply { text=getString(android.R.string.cancel); setOnClickListener {
            bootstrap?.close(); bootstrap=null
            if(targetUid>=0 && !SwitchSupportService.switchBound)sessions.clear(targetUid)
            finish()
        } }
        setContentView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,80,40,40); addView(message); addView(proceed); addView(cancel) })
        sessions=AndroidSessionStore(File(noBackupFilesDir,"migration-android"))
        try {
            // This component itself requires NETWORK_SETUP_WIZARD; the destination
            // is fixed to the installed Switch package, never an Intent-supplied UID.
            check(org.microg.gms.utils.ExtendedPackageInfo(this,SWITCH_PACKAGE).hasGooglePackagePermission(org.microg.gms.common.GooglePackagePermission.ACCOUNT))
            targetUid=packageManager.getApplicationInfo(SWITCH_PACKAGE,0).uid
            // Switch was started with this pairing. Unless it is still bound (copying), that copy has ended, copied or
            // failed, and the old phone has left it: pair afresh.
            if(sessions.launched(targetUid) && !SwitchSupportService.switchBound)sessions.clear(targetUid)
            if(sessions.load(targetUid).use { it.hint()!=null }) {
                ready(); return
            }
            val listener=object: Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.smartdevice.d2d.internal.ITargetBootstrapListener")
                    when(code) {
                        1 -> data.skipSafeParcelable()
                        2 -> handler.post { if(!canProceed)message.setText(com.google.android.gms.R.string.android_d2d_disconnected) }
                        5 -> { val text=data.readString(); handler.post { message.text=text } }
                        6 -> { val pin=data.readString(); handler.post { message.text=getString(com.google.android.gms.R.string.android_d2d_compare,pin) } }
                        7 -> { val error=data.readInt(); handler.post { message.text=getString(com.google.android.gms.R.string.android_d2d_unavailable); proceed.isEnabled=canProceed } }
                        8 -> { data.skipSafeParcelable(); handler.post { if(canProceed)ready() } }
                        else -> return false
                    }
                    data.requireEnd(); reply?.writeNoException(); return true
                }
            }
            val options=DctRecord(LegacyBootstrapExchange.schema,mapOf(3 to 1,18 to 128L,20 to 128L))
            bootstrap=LegacyTargetBootstrap(this,options,listener,{ verified ->
                sessions.commit(targetUid,verified)
                handler.post { ready() }
            },{ status -> handler.post {
                if(status==0 && !canProceed)message.text=getString(com.google.android.gms.R.string.android_d2d_find,bootstrap?.name ?: "")
                else if(status!=0)message.text=getString(com.google.android.gms.R.string.android_d2d_unavailable)
            } }).also { it.start() }
        } catch(_: Exception) { message.setText(com.google.android.gms.R.string.android_d2d_missing_switch) }
    }
    private fun ready() {
        if(isFinishing || isDestroyed)return
        canProceed=true; proceed.isEnabled=true
        message.setText(com.google.android.gms.R.string.android_d2d_ready)
    }
    private fun continueCopy() {
        if(!canProceed || !sessions.load(targetUid).use { it.hint()!=null })return
        // Account bootstrap is a separate unsupported operation. A retained
        // transport secret is not a transferred Google account or restore receipt.
        bootstrap?.close(); bootstrap=null
        try {
            startActivity(Intent("com.google.android.apps.restore.ACTION_LAUNCH_WIFI_D2D").setPackage(SWITCH_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                .putExtra("is_quick_start",true).putExtra("after_account",true))
            sessions.markLaunched(targetUid)
            finish()
        } catch(_: Exception) { message.setText(com.google.android.gms.R.string.android_d2d_missing_switch) }
    }
    override fun onDestroy() { bootstrap?.close(); bootstrap=null; super.onDestroy() }
}
