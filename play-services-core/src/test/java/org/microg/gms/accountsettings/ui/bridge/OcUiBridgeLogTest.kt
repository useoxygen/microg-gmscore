/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.accountsettings.ui.bridge

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.accountsettings.ui.MainActivity
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Google's GNOTS confirmation page calls [OcUiBridge] (useoxygen/cyclon#842). Page results, URLs it asks to open and
 * parser messages may carry tokens or capability URLs, so none of them may reach the log. Runs on Android's own
 * org.json, whose exception messages quote the whole input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OcUiBridgeLogTest {
    private val marker = "SYNTHETIC_RESULT_TOKEN"
    private val capabilityUrl = "https://myaccount.google.com/signinoptions?rapt=SYNTHETIC_CAPABILITY_RAPT"

    private lateinit var bridge: OcUiBridge

    @Before
    fun setUp() {
        ShadowLog.clear()
        // Constructed only; the bridge methods under test do not need a created activity.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        bridge = OcUiBridge(activity, "synthetic@example.invalid", null)
    }

    private fun assertNothingLeaked(vararg secrets: String) {
        val logs = ShadowLog.getLogsForTag("JS_ocUi")
        assertTrue("nothing was logged", logs.isNotEmpty())
        for (item in logs) {
            assertNull("a throwable was logged: ${item.throwable}", item.throwable)
            for (secret in secrets) assertFalse("$secret leaked: ${item.msg}", item.msg.contains(secret))
            assertFalse("account leaked: ${item.msg}", item.msg.contains("synthetic@example.invalid"))
        }
    }

    @Test
    fun theAndroidParserQuotesItsInput() {
        // Guards the premise of the next test: this is the platform parser, not the JSON-java test dependency.
        val error = assertThrows(JSONException::class.java) { JSONObject("{\"token\": \"$marker\" ") }
        assertTrue(error.message!!.contains(marker))
    }

    @Test
    fun malformedResultsLogOnlyTheExceptionClass() {
        bridge.setResult("{\"token\": \"$marker\" ")
        bridge.setResult("not json $marker")
        assertNothingLeaked(marker)
        assertTrue(ShadowLog.getLogsForTag("JS_ocUi").any { it.msg == "Unable to parse result JSON string: org.json.JSONException" })
    }

    @Test
    fun urlBearingCallbacksDoNotLogTheirArguments() {
        bridge.open(capabilityUrl)
        bridge.openHelp(capabilityUrl)
        bridge.setResult("{\"next\": \"$capabilityUrl\"}")
        assertNothingLeaked("SYNTHETIC_CAPABILITY_RAPT", "myaccount.google.com")
        val messages = ShadowLog.getLogsForTag("JS_ocUi").map { it.msg }
        assertTrue(messages.containsAll(listOf("open", "openHelp")))
    }

    /** The web-page sources must not hand an exception or a raw URL/page argument to the log. */
    @Test
    fun webSourcesLogNoThrowablesOrRawArguments() {
        val directory = File("src/main/kotlin/org/microg/gms/accountsettings/ui")
        val throwable = Regex("""Log\.\w+\([^;\n]*,\s*e\)""")
        // A whole value; derived values such as ${url.toUri().host} or ${resultJsonStr?.length} are fine.
        val raw = Regex("""Log\.\w+\([^;\n]*\$\{?(url|str|authUrl|resultJsonStr|requestUrl|accountName)\b(?![.?])""")
        for (name in listOf("WebViewHelper.kt", "MainActivity.kt", "bridge/OcUiBridge.kt")) {
            val source = File(directory, name).readText()
            assertNull("$name logs a throwable: ${throwable.find(source)?.value}", throwable.find(source))
            assertNull("$name logs a raw argument: ${raw.find(source)?.value}", raw.find(source))
        }
    }
}
