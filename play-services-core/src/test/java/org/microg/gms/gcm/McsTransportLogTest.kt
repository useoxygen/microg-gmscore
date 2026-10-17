/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.gcm

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.microg.gms.gcm.mcs.AppData
import org.microg.gms.gcm.mcs.DataMessageStanza
import org.microg.gms.gcm.mcs.HeartbeatPing
import org.microg.gms.gcm.mcs.LoginRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The MCS transport chain (useoxygen/cyclon#842): synthetic messages with marker values go out through
 * [McsOutputStream] and back in through [McsInputStream], and no marker may reach the log.
 */
class McsTransportLogTest {
    private val lines = mutableListOf<String>()

    @Before
    fun captureLogs() = McsLog.setSinkForTest { _, tag, line -> synchronized(lines) { lines += "$tag $line" } }

    @After
    fun restoreLogs() = McsLog.setSinkForTest(null)

    private val markers = listOf("SYNTHETIC_PROMPT_PAYLOAD", "synthetic@example.invalid", "SYNTHETIC_AUTHS_TOKEN", "SYNTHETIC_LOGIN_TOKEN", "SYNTHETIC_REG_ID", "SYNTHETIC_FROM_7454")

    private fun assertNoMarkers() {
        assertTrue("nothing was logged", lines.isNotEmpty())
        for (line in lines) for (marker in markers) assertFalse("$marker leaked: $line", line.contains(marker))
    }

    private fun appData(key: String, value: String) = AppData.Builder().key(key).value_(value).build()

    private val incomingPrompt = DataMessageStanza.Builder()
        .from("SYNTHETIC_FROM_7454").category("com.google.android.gms")
        .app_data(listOf(appData("gcmb", "notifications"), appData("gms.gnots.payload", "SYNTHETIC_PROMPT_PAYLOAD")))
        .build()

    private val outgoingRegistration = DataMessageStanza.Builder()
        .from("google.com").category("com.google.android.gms").reg_id("SYNTHETIC_REG_ID")
        .app_data(listOf(appData("account", "synthetic@example.invalid"), appData("auths", "SYNTHETIC_AUTHS_TOKEN")))
        .build()

    private val login = LoginRequest.Builder()
        .id("android-1").domain("mcs.android.com").user("1").resource("1").auth_token("SYNTHETIC_LOGIN_TOKEN")
        .build()

    @Test
    fun messagesRoundTripThroughTheTransportWithoutContentInLogs() {
        val wire = ByteArrayOutputStream()
        val output = McsOutputStream(wire, null, false)
        output.write(login, McsConstants.MCS_LOGIN_REQUEST_TAG)
        output.write(outgoingRegistration, McsConstants.MCS_DATA_MESSAGE_STANZA_TAG)
        output.write(incomingPrompt, McsConstants.MCS_DATA_MESSAGE_STANZA_TAG)
        output.write(HeartbeatPing.Builder().build(), McsConstants.MCS_HEARTBEAT_PING_TAG)

        val input = McsInputStream(ByteArrayInputStream(wire.toByteArray()), null, false)
        assertEquals(login, input.readMessage())
        assertEquals(outgoingRegistration, input.readMessage())
        assertEquals(incomingPrompt, input.readMessage())
        assertTrue(input.readMessage() is HeartbeatPing)

        assertNoMarkers()
        assertTrue(lines.any { it.contains("Incoming message: type=DataMessageStanza size=256 category=com.google.android.gms app_data=2") })
        assertTrue(lines.any { it.contains("Outgoing message: type=LoginRequest size=256") })
    }

    @Test
    fun malformedFramesAndErrorsLogOnlyClassesAndNumbers() {
        // A data message frame whose body is cut off mid-field, followed by an unknown tag.
        val body = incomingPrompt.encode()
        val frame = byteArrayOf(41, McsConstants.MCS_DATA_MESSAGE_STANZA_TAG.toByte(), body.size.toByte()) + body.copyOf(body.size - 3)
        val input = McsInputStream(ByteArrayInputStream(frame), null, false)
        try {
            input.readMessage()
        } catch (e: IOException) {
            McsLog.w("test", "I/O error: " + McsLog.describe(e))
        } catch (e: RuntimeException) {
            McsLog.w("test", McsLog.describe(e))
        }
        val unknown = McsInputStream(ByteArrayInputStream(byteArrayOf(41, 99, 0)), null, false)
        assertNull(unknown.readMessage())

        val failure = IllegalStateException("bad SYNTHETIC_AUTHS_TOKEN", IOException("for synthetic@example.invalid"))
        McsLog.w("test", McsLog.describe(failure))
        assertTrue(lines.any { it.contains("error=IllegalStateException<-IOException") })
        assertNoMarkers()
    }

    @Test
    fun categoriesThatAreNotPackageNamesAreNotEchoed() {
        assertEquals("com.example.app", McsLog.safeCategory("com.example.app"))
        assertEquals("?", McsLog.safeCategory("synthetic@example.invalid"))
        assertEquals("?", McsLog.safeCategory("a b"))
        assertEquals("none", McsLog.safeCategory(null))
        assertEquals("type=none", McsLog.describe(null as Any?))
    }

    /** The transport classes must log through [McsLog], never by concatenating a message, Intent or Bundle. */
    @Test
    fun transportSourcesDoNotLogMessagesOrExtras() {
        val directory = File("src/main/java/org/microg/gms/gcm")
        val unsafe = Regex("""Log\.\w+\([^;]*\+\s*(message|msg|msg\.obj|extras|intent|intent\.getExtras\(\)|bundle)\s*[,)]""")
        for (name in listOf("McsInputStream.java", "McsOutputStream.java", "SendReceiver.java", "McsService.java")) {
            val source = File(directory, name).readText()
            val match = unsafe.find(source)
            assertNull("$name logs content: ${match?.value}", match)
        }
    }
}
