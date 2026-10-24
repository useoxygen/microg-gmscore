/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.migration.lab

import android.app.Activity
import android.os.*
import android.widget.TextView
import org.microg.gms.migration.*
import org.microg.gms.migration.android.*
import org.microg.gms.migration.nearby.DctRecord
import java.io.File

/** Optional real SmartDevice UI experiment. No secret injection or Google caller
 * spoofing; the production source must actually discover this NC_ID endpoint. */
class BootstrapActivity : Activity() {
    private var bootstrap: LegacyTargetBootstrap?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val text=TextView(this).apply { textSize=22f; setPadding(40,80,40,40) }; setContentView(text)
        val store=AndroidSessionStore(File(noBackupFilesDir,"bootstrap-lab"))
        fun status(value: String) { File(filesDir,"bootstrap-result.txt").writeText(value+"\n"); runOnUiThread { text.text=value } }
        val listener=object: Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.smartdevice.d2d.internal.ITargetBootstrapListener")
                when(code) {
                    1 -> { data.skipSafeParcelable(); status("SOURCE_CONNECTED") }
                    2 -> status("SOURCE_DISCONNECTED")
                    5 -> { data.readString(); status("SOURCE_DISPLAY_TEXT") }
                    6 -> {
                        val pin=requireNotNull(data.readString())
                        // A one-way digest lets the host compare the source's visible
                        // code without printing or retaining the raw code/token.
                        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                        File(filesDir,"bootstrap-pin-sha256.txt").writeText(digest)
                        status("PIN_RECEIVED (not logged)")
                        runOnUiThread { text.text="Compare on the old phone: $pin" }
                    }
                    7 -> status("SOURCE_FAILURE="+data.readInt())
                    8 -> { data.skipSafeParcelable(); status("SOURCE_COMPLETED") }
                    else -> return false
                }
                data.requireEnd(); reply?.writeNoException(); return true
            }
        }
        status("STARTING (source must use the real exported Google setup UI)")
        bootstrap=LegacyTargetBootstrap(this,DctRecord(LegacyBootstrapExchange.schema,mapOf(3 to 1,18 to 128L,20 to 128L)),listener,{ verified -> store.commit(Process.myUid(),verified); status("ACKNOWLEDGED_PERSISTED (no credentials logged)") },{ code -> status("ADVERTISING_STATUS=$code") }).also { it.start() }
    }
    override fun onDestroy() { bootstrap?.close(); super.onDestroy() }
}
