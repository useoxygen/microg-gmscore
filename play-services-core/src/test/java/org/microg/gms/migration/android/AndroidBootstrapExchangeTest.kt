/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.*
import android.util.Base64
import com.google.android.gms.common.api.Status
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import com.google.security.cryptauth.lib.securemessage.CryptoOps
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Source JSON is a synthetic fixture. This is NOT real SmartDevice/GMS bootstrap acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE,sdk = [35])
class AndroidBootstrapExchangeTest {
    @get:Rule val temp = TemporaryFolder()
    private val token = ByteArray(32) { (it+1).toByte() }
    private fun response(session: String? = null): ByteArray { val inner = JSONObject().put("wifi_network",JSONObject().put("wifi_security_type","Unsupported")); session?.let { inner.put("SESSION_ID",it.toLong()) }; return JSONObject().put("protocolVersion",3).put("quickStartPayload",Base64.encodeToString(inner.toString().toByteArray(),Base64.NO_WRAP)).toString().toByteArray() }
    private fun query(binder: IBinder, operation: Int, event: Int, schema: Map<Int,DctRecord.Kind>?, input: DctRecord? = null): DctRecord? {
        var answer: DctRecord? = null; var called = false
        val callback = object : Binder() { override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(PAIR_CALLBACK); assertEquals(event,code); assertEquals(1,data.readInt()); assertEquals(0,Status.CREATOR.createFromParcel(data).statusCode)
            if(schema != null) answer = DctRecord.nullable(data,schema)
            data.requireEnd(); called = true; return true
        } }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { data.writeInterfaceToken(TARGET_DESCRIPTOR); data.writeStrongBinder(callback); if(input != null)data.writeNullable(input); data.writeInt(0); assertTrue(binder.transact(operation,data,reply,0)); reply.readException() } finally { data.recycle(); reply.recycle() }
        assertTrue(called); return answer
    }
    @Test fun confirmedBootstrapSurvivesFreshReaderThenTarget13_14_15_17AgreesWithPeer() {
        val root = temp.newFolder(); val uid = Binder.getCallingUid(); val store = AndroidSessionStore(root)
        val exchange = AndroidBootstrapExchange(token) { store.commit(uid,it) }
        assertThrows(IllegalStateException::class.java) { exchange.wifiRequest() }
        exchange.confirmPeer(token)
        val request = JSONObject(exchange.wifiRequest().toString(Charsets.UTF_8))
        val wifi = JSONObject(Base64.decode(request.getString("quickStartPayload"),Base64.DEFAULT).toString(Charsets.UTF_8))
        val session = wifi.getLong("SESSION_ID").toString(); val secret = Base64.decode(wifi.getString("shared_secret"),Base64.URL_SAFE)
        assertNull(store.load(uid).hint())
        exchange.receivedAuthenticatedResponse(response(session))
        assertPostSetup(root,uid,session,secret)
    }
    @Test fun legacySourceAcknowledgementPersistsThen13_14_15_17AgreesWithPeer() {
        val root=temp.newFolder(); val uid=Binder.getCallingUid(); val store=AndroidSessionStore(root)
        LegacyBootstrapExchange(DctRecord(LegacyBootstrapExchange.schema,mapOf(3 to 1,18 to 128L))) { store.commit(uid,it) }.use { exchange ->
            val options=JSONObject(exchange.initial("Cyclon (712)").toString(Charsets.UTF_8)).getJSONObject("bootstrapOptions")
            val secret=Base64.decode(options.getString("SharedSecret"),Base64.DEFAULT)
            assertEquals(1,options.getInt("accountRequirement"))
            assertEquals("intent:#Intent;action=com.google.android.gms.backup.ACTION_WIFI_D2D;package=com.google.android.gms;end",options.getJSONObject("PostTransferAction").getString("wifiD2D"))
            assertNull(store.load(uid).hint())
            exchange.receive("{\"bootstrapConfigurations\":{\"optionFlags\":8}}".toByteArray())
            assertPostSetup(root,uid,exchange.sessionId.toString(),secret)
        }
    }
    private fun assertPostSetup(root: File, uid: Int, session: String, secret: ByteArray) {
        val fresh = AndroidSessionStore(root).load(uid)
        AndroidTargetDeviceBinder(uid,fresh).use { target ->
            val hint = query(target,13,1,AndroidSetupSchemas.hint)!!; assertEquals(session,hint.text(2)); assertArrayEquals(secret,hint.bytes(3))
            val source = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)
            source.parseHandshakeMessage(query(target,14,2,AndroidSetupSchemas.handshake)!!.bytes(2))
            val finish = query(target,15,4,AndroidSetupSchemas.handshake,DctRecord(AndroidSetupSchemas.handshake,mapOf(2 to source.nextHandshakeMessage)))!!
            source.parseHandshakeMessage(finish.bytes(2)); val verification = source.getVerificationString(32)
            assertArrayEquals(PostSetupTargetHandshake.hmac(secret,verification),finish.bytes(3)); source.verifyHandshake()
            val context = source.toConnectionContext(); val challenge = ByteArray(32) { 22 }
            val auth = query(target,17,5,AndroidSetupSchemas.auth,DctRecord(AndroidSetupSchemas.handshake,mapOf(2 to challenge,3 to PostSetupTargetHandshake.hmac(secret,challenge))))!!
            assertArrayEquals(CryptoOps.hkdf(SecretKeySpec(context.sessionUnique,"HmacSHA256"),secret,"smartdevice.postsetup.key".toByteArray(),32),auth.bytes(2))
            query(target,23,9,null)
        }
        assertNull(AndroidSessionStore(root).load(uid).hint())
    }
    @Test fun wrongPeerReplayOrUnacknowledgedRequestCannotPersistASecret() {
        var commits = 0
        AndroidBootstrapExchange(token) { commits++; it.close() }.use { exchange ->
            assertThrows(SecurityException::class.java) { exchange.confirmPeer(ByteArray(32)) }
            assertThrows(IllegalStateException::class.java) { exchange.wifiRequest() }
        }
        AndroidBootstrapExchange(token) { commits++; it.close() }.use { exchange ->
            exchange.confirmPeer(token); exchange.wifiRequest()
            assertThrows(IllegalArgumentException::class.java) { exchange.receivedAuthenticatedResponse("{\"protocolVersion\":3,\"quickStartPayload\":\"e30=\"}".toByteArray()) }
            assertThrows(IllegalStateException::class.java) { exchange.receivedAuthenticatedResponse(response()) }
        }
        assertEquals(0,commits)
    }
    @Test fun googleAccountPayloadIsUnavailableRatherThanAnEmptySuccessfulTransfer() {
        var committed = false
        AndroidBootstrapExchange(token) { committed = true; it.close() }.use { exchange -> exchange.confirmPeer(token); exchange.wifiRequest(); assertThrows(UnsupportedOperationException::class.java) { exchange.receivedAuthenticatedResponse("{\"accountBootstrapPayload\":{}}".toByteArray()) } }
        assertFalse(committed)
    }
    @Test fun uidIsolationExpiryAndCorruptFileFailClosedAfterRestart() {
        val root = temp.newFolder(); var clock = 1000000L
        val store = AndroidSessionStore(root) { clock }
        store.commit(42,AndroidPostSetupState.VerifiedBootstrap("123",ByteArray(32) { 9 }))
        assertNull(store.load(43).hint())
        val retained = store.load(42); assertNotNull(retained.hint())
        clock += AndroidSessionStore.TTL+1; assertNull(retained.hint()); assertNull(store.load(42).hint())
        File(root,"session-42").writeBytes(byteArrayOf(1,2,3)); assertNull(store.load(42).hint()); assertFalse(File(root,"session-42").exists())
    }
}
