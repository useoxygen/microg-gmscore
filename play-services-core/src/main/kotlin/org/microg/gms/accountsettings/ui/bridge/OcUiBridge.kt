// Modified by Cyclon in 2026: no page results, opened URLs, parser messages or account names in logs.
/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.accountsettings.ui.bridge

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject
import org.microg.gms.accountsettings.ui.EXTRA_ACCOUNT_NAME
import org.microg.gms.accountsettings.ui.EXTRA_SCREEN_ID
import org.microg.gms.accountsettings.ui.KEY_UPDATED_PHOTO_URL
import org.microg.gms.accountsettings.ui.MainActivity
import org.microg.gms.accountsettings.ui.finishActivity
import org.microg.gms.accountsettings.ui.runOnMainLooper

class OcUiBridge(val activity: MainActivity, val accountName:String?, val webView: WebView?) {

    companion object{
        const val NAME = "ocUi"
        private const val TAG = "JS_$NAME"
    }

    private var resultBundle: Bundle? = null

    @JavascriptInterface
    fun close() {
        Log.d(TAG, "close: ")
        val intent = Intent()
        if (resultBundle != null) {
            intent.putExtras(resultBundle!!)
        }
        accountName?.let { activity.updateVerifyNotification(it) }
        activity.setResult(RESULT_OK, intent)
        activity.finishActivity()
    }

    @JavascriptInterface
    fun closeWithResult(resultJsonStr: String?) {
        Log.d(TAG, "closeWithResult: ${resultJsonStr?.length ?: 0} chars")
        setResult(resultJsonStr)
        close()
    }

    @JavascriptInterface
    fun goBackOrClose() {
        Log.d(TAG, "goBackOrClose: ")
        activity.onBackPressed()
    }

    @JavascriptInterface
    fun hideKeyboard() {
        Log.d(TAG, "hideKeyboard: ")
    }

    @JavascriptInterface
    fun isCloseWithResultSupported(): Boolean {
        Log.d(TAG, "isCloseWithResultSupported: ")
        return true
    }

    @JavascriptInterface
    fun isOpenHelpEnabled(): Boolean {
        Log.d(TAG, "isOpenHelpEnabled: ")
        return true
    }

    @JavascriptInterface
    fun isOpenScreenEnabled(): Boolean {
        Log.d(TAG, "isOpenScreenEnabled: ")
        return true
    }

    @JavascriptInterface
    fun isSetResultSupported(): Boolean {
        Log.d(TAG, "isSetResultSupported: ")
        return true
    }

    @JavascriptInterface
    fun open(str: String?) {
        Log.d(TAG, "open")
    }

    @JavascriptInterface
    fun openHelp(str: String?) {
        Log.d(TAG, "openHelp")
    }

    @JavascriptInterface
    fun openScreen(screenId: Int, str: String?) {
        Log.d(TAG, "openScreen: screenId -> $screenId")
        val intent = Intent(activity, MainActivity::class.java).apply {
            putExtra(EXTRA_SCREEN_ID, screenId)
            putExtra(EXTRA_ACCOUNT_NAME, accountName)
        }
        activity.startActivity(intent)
    }

    @JavascriptInterface
    fun setBackStop() {
        Log.d(TAG, "setBackStop: ")
        runOnMainLooper { webView?.clearHistory() }
    }

    @JavascriptInterface
    fun setResult(resultJsonStr: String?) {
        Log.d(TAG, "setResult: ${resultJsonStr?.length ?: 0} chars")
        val map = jsonToMap(resultJsonStr) ?: return
        if (map.containsKey(KEY_UPDATED_PHOTO_URL)) {
            activity.updateLocalAccountAvatar(map[KEY_UPDATED_PHOTO_URL], accountName)
        }
        resultBundle = Bundle().apply {
            for ((key, value) in map) {
                putString("result.$key", value)
            }
        }
    }

    private fun jsonToMap(jsonStr: String?): Map<String, String>? {
        val hashMap = HashMap<String, String>()
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val jSONObject = JSONObject(jsonStr)
                val keys = jSONObject.keys()
                while (keys.hasNext()) {
                    val next = keys.next()
                    val obj = jSONObject[next]
                    hashMap[next] = obj as String
                }
            } catch (e: JSONException) {
                // Android's JSONException message quotes the whole input, so only the class is logged.
                Log.d(TAG, "Unable to parse result JSON string: ${e.javaClass.name}")
                return null
            }
        }
        return hashMap
    }

}