/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Ordinary app identity and AccountManager consent; no borrowed OAuth client or token export. */
class ContactsLabActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var authorize: Button
    private lateinit var account: Account
    private val worker = Executors.newSingleThreadExecutor()
    private val receipt = JSONObject().put("operation", "read-only-google-contacts-probe")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        output = TextView(this).apply { textSize = 18f }
        authorize = Button(this).apply { text = "Verify Google contacts access" }
        layout.addView(output)
        layout.addView(authorize)
        setContentView(layout)
        if (Build.TYPE !in listOf("userdebug", "eng")) {
            authorize.isEnabled = false
            output.text = "This test app requires a development phone."
            return
        }
        val name = intent.getStringExtra("accountName")?.takeIf { it.isNotBlank() }
        if (name == null) {
            authorize.isEnabled = false
            output.text = "Launch with the exact Google account name to test."
            return
        }
        account = Account(name, "com.google")
        receipt.put("accountSha256", AccessProbe.receipt(account).getString("accountSha256"))
        output.text = "Selected account: $name\n\nThis check reads Google contacts without changing them."
        authorize.setOnClickListener { requestAuthorization() }
    }

    private fun status(value: String) {
        receipt.put("status", value)
        File(filesDir, "access-probe.json").writeText(receipt.toString(2))
        runOnUiThread { output.text = "Selected account: ${account.name}\n\n$value" }
    }

    private fun requestAuthorization() {
        authorize.isEnabled = false
        status("Requesting read-only Google authorization")
        // Android supplies and verifies our actual package and UID. No override extras.
        AccountManager.get(this).getAuthToken(account,
            "oauth2:https://www.googleapis.com/auth/contacts.readonly", null, this, { future ->
                try {
                    val result = future.result
                    check(result.getString(AccountManager.KEY_ACCOUNT_NAME) == account.name)
                    check(result.getString(AccountManager.KEY_ACCOUNT_TYPE) == account.type)
                    val token = result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                        ?: error("Missing contacts authorization")
                    worker.execute { verify(token) }
                } catch (e: Exception) {
                    AccessProbe.errorCode(e)?.let { receipt.put("authReason", it) }
                    receipt.put("exception", e.javaClass.simpleName)
                    status("Authorization did not complete (${e.javaClass.simpleName})")
                    authorize.isEnabled = true
                }
            }, null)
    }

    private fun verify(token: String) {
        val value = AccessProbe.verify(this, receipt, token)
        runOnUiThread {
            output.text = "Selected account: ${account.name}\n\n$value"
            authorize.isEnabled = true
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
