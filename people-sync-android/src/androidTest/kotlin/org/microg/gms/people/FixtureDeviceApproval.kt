/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Shared exact-device proof used by every provider fixture. */
object FixtureDeviceApproval {
    fun approved(context: android.content.Context): Boolean {
        if (Build.HARDWARE in listOf("ranchu", "goldfish")) return true
        // Default runs still refuse physical phones. The caller must supply the
        // exact connected serial, and the phone must be a development build.
        if (Build.TYPE !in listOf("userdebug", "eng") || Build.VERSION.SDK_INT < 29) return false
        val arguments = InstrumentationRegistry.getArguments()
        val expected = arguments.getString("physicalDeviceSerial")
            ?.takeIf { it.isNotBlank() } ?: return false
        val nonce = arguments.getString("physicalDeviceNonce")?.takeIf { it.isNotBlank() } ?: return false
        // The serial-pinned host runner reads ro.serialno through ADB and writes
        // a fresh, one-run proof into this debug APK's private storage. Avoid
        // UiAutomation entirely so another agent can retain its screen connection.
        return runCatching {
            val proof = JSONObject(File(context.filesDir, "physical-device-authorization.json").readText())
            val age = System.currentTimeMillis() - proof.getLong("createdAt")
            proof.getString("serial") == expected && proof.getString("nonce") == nonce &&
                proof.getString("fingerprint") == Build.FINGERPRINT && age in 0..300_000
        }.getOrDefault(false)
    }
}
