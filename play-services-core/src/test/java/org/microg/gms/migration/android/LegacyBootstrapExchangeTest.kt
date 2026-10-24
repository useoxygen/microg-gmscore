/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.migration.nearby.DctRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class LegacyBootstrapExchangeTest {
    @Test fun targetIssuedSecretRequiresSourceFeatureAcknowledgement() {
        var retained: AndroidPostSetupState.VerifiedBootstrap?=null
        LegacyBootstrapExchange(DctRecord(LegacyBootstrapExchange.schema,mapOf(18 to 128L))) { retained=it }.use { exchange ->
            val initial=JSONObject(exchange.initial("Cyclon (712)").toString(Charsets.UTF_8)).getJSONObject("bootstrapOptions")
            val secret=Base64.decode(initial.getString("SharedSecret"),Base64.DEFAULT)
            assertEquals(32,secret.size); assertEquals(exchange.sessionId,initial.getLong("sessionId"))
            assertEquals(128L or 2048L,initial.getLong("optionFlags")); assertFalse(initial.getBoolean("supportsAccountTransferImport"))
            assertNull(retained)
            exchange.receive("{\"bootstrapConfigurations\":{\"optionFlags\":8,\"sessionId\":1,\"SharedSecret\":\"wrong\"}}".toByteArray())
            retained!!.use { assertEquals(exchange.sessionId.toString(),it.hint().text(2)); assertArrayEquals(secret,it.hint().bytes(3)) }
            assertEquals(2,exchange.receive("{\"bootstrapState\":2}".toByteArray()))
            assertThrows(IllegalStateException::class.java) { exchange.receive("{\"bootstrapConfigurations\":{\"optionFlags\":8}}".toByteArray()) }
        }
    }
    @Test fun noAcknowledgementCannotCompleteAndAccountsReturnActualFailure() {
        var committed=false
        LegacyBootstrapExchange(DctRecord(LegacyBootstrapExchange.schema)) { committed=true }.use { exchange ->
            exchange.initial("Cyclon")
            exchange.receive("{\"bootstrapConfigurations\":{\"optionFlags\":0}}".toByteArray())
            assertFalse(committed)
            assertThrows(IllegalStateException::class.java) { exchange.receive("{\"bootstrapState\":2}".toByteArray()) }
            val failure=assertThrows(LegacyBootstrapExchange.BootstrapFailure::class.java) { exchange.receive("{\"accountBootstrapPayload\":{}}".toByteArray()) }
            assertEquals(10573,failure.status); assertFalse(committed)
            val error=JSONObject(exchange.terminal(3,failure.status).toString(Charsets.UTF_8))
            assertEquals(3,error.getInt("bootstrapState")); assertEquals(10573,error.getJSONObject("deviceStatus").getInt("errorCode"))
        }
        assertEquals(432000000L,AndroidSessionStore.TTL)
    }
}
