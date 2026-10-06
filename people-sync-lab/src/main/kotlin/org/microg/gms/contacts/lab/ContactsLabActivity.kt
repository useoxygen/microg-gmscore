/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import java.io.File
import java.util.concurrent.Executors

/** Public Google auth client and microG-issued recovery intent; no token export or identity override. */
class ContactsLabActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var authorize: Button
    private lateinit var account: Account
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var receipt: org.json.JSONObject
    private var consentAttempts = 0
    private var roundTripNonce: String? = null

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
        receipt = AccessProbe.receipt(account)
        roundTripNonce = intent.getStringExtra("roundTripNonce")
        if (roundTripNonce != null) {
            try { LiveRoundTrip.checkProof(this, account, roundTripNonce!!) }
            catch (e: Exception) {
                authorize.isEnabled = false
                output.text = "Disposable test refused (${e.javaClass.simpleName}). Fresh device proof is required."
                return
            }
            receipt.put("operation", "google-contacts-round-trip")
            authorize.text = "Test disposable contact round trip"
        }
        output.text = "Selected account: $name\n\n" + if (roundTripNonce == null)
            "This check reads Google contacts without changing them." else
            "This test creates, edits and removes one temporary Google contact using an isolated provider account."
        authorize.setOnClickListener { requestAuthorization() }
    }

    private fun status(value: String) {
        receipt.put("status", value)
        File(filesDir, "access-probe.json").writeText(receipt.toString(2))
        runOnUiThread { output.text = "Selected account: ${account.name}\n\n$value" }
    }

    private fun requestAuthorization() {
        authorize.isEnabled = false
        status(if (roundTripNonce == null) "Requesting read-only Google authorization" else "Requesting Google authorization for the disposable test")
        worker.execute {
            try {
                // microG's Google auth service returns a recovery intent backed by its own
                // PendingIntent. AccountManager's raw intent targets a private activity.
                // The SDK supplies our actual package and UID, with no override extras.
                val token = GoogleAuthUtil.getToken(this, account,
                    "oauth2:https://www.googleapis.com/auth/contacts" + if (roundTripNonce == null) ".readonly" else "")
                check(token.isNotBlank())
                verify(token)
            } catch (e: UserRecoverableAuthException) {
                runOnUiThread {
                    try {
                        check(++consentAttempts <= 3)
                        status("Waiting for Google contacts consent")
                        val recovery = requireNotNull(e.intent)
                        val pending = if (recovery.component?.packageName == "com.google.android.gms" &&
                            recovery.component?.className == "org.microg.gms.ui.UnpackingRedirectActivity") {
                            @Suppress("DEPRECATION")
                            recovery.getParcelableExtra<PendingIntent>("target")
                        } else null
                        if (pending != null) {
                            check(pending.creatorPackage == "com.google.android.gms")
                            // The installed redirect wrapper has a NoDisplay/AppCompat theme
                            // crash. Send its issuer-created PendingIntent directly; the
                            // same private activity still checks our identity and consent.
                            startIntentSenderForResult(pending.intentSender, CONSENT_REQUEST,
                                null, 0, 0, 0)
                        } else startActivityForResult(recovery, CONSENT_REQUEST)
                    } catch (launchError: Exception) {
                        authorizationFailed(launchError)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { authorizationFailed(e) }
            }
        }
    }

    private fun authorizationFailed(error: Exception) {
        AccessProbe.errorCode(error)?.let { receipt.put("authReason", it) }
        receipt.put("exception", error.javaClass.simpleName)
        status("Authorization did not complete (${error.javaClass.simpleName})")
        authorize.isEnabled = true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONSENT_REQUEST) {
            if (resultCode == RESULT_OK) requestAuthorization()
            else {
                status("Google contacts consent was cancelled")
                authorize.isEnabled = true
            }
        }
    }

    private fun verify(token: String) {
        val value = if (roundTripNonce == null) AccessProbe.verify(this, receipt, token)
            else LiveRoundTrip.run(this, account, token, roundTripNonce!!, receipt) { status(it) }
        runOnUiThread {
            output.text = "Selected account: ${account.name}\n\n$value"
            authorize.isEnabled = true
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val CONSENT_REQUEST = 1
    }
}
