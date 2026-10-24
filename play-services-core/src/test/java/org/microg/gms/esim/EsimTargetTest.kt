package org.microg.gms.esim

import android.os.*
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EsimTargetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val iccid = "8901234567890123456"
    private fun start(): EsimWireRecord {
        val config = EsimWireRecord.build { Writer.write(it, 1, 123); Writer.write(it, 5, 2)
            Writer.write(it, 8, "https://carrier.example.invalid/entitlement", false); Writer.write(it, 19, "8.0", false); Writer.write(it, 28, true) }
        return config.use { EsimWireRecord.build { parcel -> Writer.write(parcel, 1, 1); Writer.write(parcel, 2, 3); Writer.write(parcel, 6, listOf(it), 0, false) } }
    }
    private inner class Peer : EsimPeer {
        var session = 0L; var closed = false; val notifications = mutableListOf<EsimProto>()
        private fun item() = EsimProto.text(1, iccid) + EsimProto.text(2, "310") + EsimProto.text(3, "260") +
            EsimProto.text(6, "Synthetic carrier") + EsimProto.number(12, 2) +
            EsimProto.bytes(10, EsimProto.text(1, "synthetic-transfer-token") + EsimProto.text(2, "2099-01-01T00:00:00Z"))
        override fun exchange(envelope: ByteArray, expectedField: Int): EsimProto {
            val request = EsimProto.decode(envelope)
            return when (expectedField) {
                2 -> {
                    session = request.message(1)!!.number(2)
                    EsimProto.decode(EsimProto.number(2, session) + EsimProto.text(3, "1") + EsimProto.text(4, "Fixture iPhone") + EsimProto.bytes(5, item()))
                }
                4 -> {
                    assertEquals(session, request.message(3)!!.number(1)); assertEquals(iccid, request.message(3)!!.text(2))
                    EsimProto.decode(EsimProto.number(2, session) + EsimProto.bytes(3, item()))
                }
                else -> throw AssertionError("unexpected protocol request")
            }
        }
        override fun notify(envelope: ByteArray) { notifications += EsimProto.decode(envelope) }
        override fun close() { closed = true }
    }
    private fun reply() = """<wap-provisioningdoc><characteristic type="APPLICATION"><parm name="OperationResult" value="0"/><parm name="SubscriptionResult" value="2"/><characteristic type="DownloadInfo"><parm name="ProfileSmdpAddress" value="smdp.example.invalid"/><parm name="ProfileActivationCode" value="synthetic-code"/></characteristic></characteristic></wap-provisioningdoc>"""
    private fun profile(engine: EsimTransferEngine): EsimWireRecord = engine.retrieveProfiles().use { it.list(2).single() }
    @Test fun pinnedProfileRequestGoldenBytesAndBounds() {
        val bytes = "1a0e0807120a31323334353637383930".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = EsimProto.envelope(3, EsimProto.number(1, 7) + EsimProto.text(2, "1234567890"))
        assertArrayEquals(bytes, request); assertEquals(7L, EsimProto.decode(bytes).message(3)!!.number(1))
        assertThrows(IllegalArgumentException::class.java) { EsimProto.decode(byteArrayOf(0x1a, 127, 1)) }
        assertThrows(IllegalArgumentException::class.java) { EsimProto.decode(ByteArray(1024 * 1024 + 1)) }
    }
    @Test fun losslessCarrierFieldsRemainRedactedAndMalformedSafeObjectsFail() {
        val wire = EsimWireRecord.build { Writer.write(it, 1, "synthetic-secret", false); Writer.write(it, 199, "future-carrier-field", false) }
        wire.use {
            val changed = wire.replace(mapOf(1 to { parcel -> Writer.write(parcel, 1, "changed", false) }))
            changed.use { assertEquals("future-carrier-field", it.text(199)); assertEquals("changed", it.text(1)); assertFalse(it.toString().contains("changed")) }
        }
        val parcel = Parcel.obtain()
        try { parcel.writeInt(0xffff4f45.toInt()); parcel.writeInt(Int.MAX_VALUE); parcel.setDataPosition(0)
            assertThrows(RuntimeException::class.java) { EsimWireRecord.CREATOR.createFromParcel(parcel) } }
        finally { parcel.recycle() }
    }
    @Test fun targetTokenIsNotAnInstalledSimAndRepeatedReadDoesNotReplayTransfer() {
        val peer = Peer(); val urls = mutableListOf<okhttp3.HttpUrl>()
        val entitlement = EsimEntitlement({ EsimTargetIdentity("123456789012345", "1".repeat(32)) }, EsimEntitlementHttp { url -> urls += url; 200 to reply() })
        val engine = EsimTransferEngine(context, Process.myUid(), entitlement, { peer }, { 123 })
        start().use { engine.start(it) }
        profile(engine).let { first -> engine.credential(first).use { credential ->
            assertEquals("LPA:1\$smdp.example.invalid\$synthetic-code", credential.text(1)); assertFalse(credential.bool(2))
        } }
        profile(engine).let { second -> engine.credential(second).close() }
        assertEquals(listOf("ManageSubscription", "AcquireConfiguration"), urls.map { it.queryParameter("operation") })
        assertEquals("3", urls.first().queryParameter("operation_type")); assertEquals("ap2009", urls.first().queryParameter("app"))
        assertEquals("synthetic-transfer-token", urls.first().queryParameter("temporary_token"))
        assertTrue(peer.notifications.isEmpty()); engine.close()
    }
    @Test fun lostCarrierReplyIsReconciledAndLpaFailureNeverBecomesXosSuccess() {
        val peer = Peer(); val operations = mutableListOf<String?>()
        val entitlement = EsimEntitlement({ EsimTargetIdentity("123456789012345", "1".repeat(32)) }, EsimEntitlementHttp { url ->
            operations += url.queryParameter("operation")
            if (operations.size == 1) throw IOException("synthetic transport interruption")
            200 to reply()
        })
        val engine = EsimTransferEngine(context, Process.myUid(), entitlement, { peer }, { 123 })
        start().use { engine.start(it) }
        val failed = assertThrows(EsimFailure::class.java) { engine.credential(profile(engine)) }; assertEquals(7, failed.status)
        engine.credential(profile(engine)).close()
        assertEquals(listOf("ManageSubscription", "AcquireConfiguration"), operations)
        val record = profile(engine); engine.notifyDownload(record, 3); record.close()
        val message = peer.notifications.single().message(5)!!
        assertEquals(8L, message.messages(2).single().number(2))
        assertEquals(1, EsimTransferEngine.downloadStatus(0)); assertEquals(5, EsimTransferEngine.downloadStatus(1)); engine.close()
    }
    @Test fun carrierRefusalAndMissingPairDoNotReturnSuccess() {
        val peer = Peer()
        val entitlement = EsimEntitlement({ EsimTargetIdentity("123456789012345", "1".repeat(32)) }, EsimEntitlementHttp { 403 to "" })
        val engine = EsimTransferEngine(context, Process.myUid(), entitlement, { peer }, { 123 })
        start().use { engine.start(it) }
        assertEquals(48552, assertThrows(EsimFailure::class.java) { engine.credential(profile(engine)) }.status)
        engine.close()
        assertEquals(48502, assertThrows(EsimFailure::class.java) { engine.retrieveProfiles() }.status)
        assertEquals(48522, assertThrows(EsimFailure::class.java) {
            EsimWireRecord.build { Writer.write(it, 1, 2) }.use { engine.start(it) }
        }.status)
    }
    @Test fun unsupportedCarrierReplyCannotReplayAMutationAndCancelClosesPeer() {
        val peer = Peer(); val operations = mutableListOf<String?>()
        val entitlement = EsimEntitlement({ EsimTargetIdentity("123456789012345", "1".repeat(32)) }, EsimEntitlementHttp { url ->
            operations += url.queryParameter("operation")
            200 to if (operations.size == 1) reply().replace("value=\"2\"", "value=\"8\"") else reply()
        })
        val engine = EsimTransferEngine(context, Process.myUid(), entitlement, { peer }, { 123 })
        start().use { engine.start(it) }
        assertEquals(48538, assertThrows(EsimFailure::class.java) { engine.credential(profile(engine)) }.status)
        engine.credential(profile(engine)).close()
        assertEquals(listOf("ManageSubscription", "AcquireConfiguration"), operations)
        engine.cancel(); assertTrue(peer.closed)
        assertEquals(16, assertThrows(EsimFailure::class.java) { engine.credential(profile(engine)) }.status)
        engine.close()
    }
    @Test fun freshSessionUuidAfterProcessLossCannotReplayCarrierMutation() {
        val operations = mutableListOf<String?>()
        val entitlement = EsimEntitlement({ EsimTargetIdentity("123456789012345", "1".repeat(32)) }, EsimEntitlementHttp { url ->
            operations += url.queryParameter("operation")
            if (operations.size == 1) throw IOException("synthetic unknown carrier outcome")
            200 to reply()
        })
        fun engine() = EsimTransferEngine(context, Process.myUid(), entitlement, { Peer() }, {123})
        val first = engine(); start().use { first.start(it) }
        val before = profile(first); val previousId = before.text(4)
        assertEquals(7, assertThrows(EsimFailure::class.java) { first.credential(before) }.status)
        first.close()
        val second = engine(); start().use { second.start(it) }
        val after = profile(second); assertNotEquals(previousId, after.text(4)); second.credential(after).close()
        assertEquals(listOf("ManageSubscription", "AcquireConfiguration"), operations); second.close()
    }
    @Test fun downloadReportMustMatchAuthenticatedSessionIccid() {
        val peer = Peer(); val engine = EsimTransferEngine(context, Process.myUid(), peerFactory = { peer }, carrierId = {123})
        start().use { engine.start(it) }
        val original = profile(engine)
        val identity = original.obj(2)!!
        val other = identity.replace(mapOf(1 to { Writer.write(it, 1, "8909999999999999999", false) }))
        val changed = original.replace(mapOf(2 to { Writer.write(it, 2, other, 0, false) }))
        assertEquals(48508, assertThrows(EsimFailure::class.java) { engine.notifyDownload(changed, 0) }.status)
        assertTrue(peer.notifications.isEmpty()); identity.close(); other.close(); original.close(); engine.close()
    }
    @Test fun missingSessionCallbackContainsFailureAndNoProfileRatherThanEmptySuccess() {
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    data.enforceInterface(EsimTransferBinder.PREFIX + "IRetrieveProfilesCallbacks")
                    assertEquals(1, code); assertEquals(IBinder.FLAG_ONEWAY, flags)
                    assertEquals(1, data.readInt()); assertEquals(48502, Status.CREATOR.createFromParcel(data).statusCode)
                    assertEquals(0, data.readInt()); assertEquals(0, data.dataAvail())
                } catch (e: Throwable) { failure.set(e) } finally { done.countDown() }
                return true
            }
        }
        val binder = EsimTransferBinder(context, Binder.getCallingUid(), permission = {})
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(EsimTransferBinder.DESCRIPTOR); data.writeStrongBinder(callback)
            assertTrue(binder.transact(1, data, reply, 0)); reply.readException()
            assertTrue(done.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        } finally { data.recycle(); reply.recycle(); binder.close() }
    }
    @Test fun carrierXmlDoesNotAcceptExternalEntitiesOrFalseSuccess() {
        assertThrows(IllegalArgumentException::class.java) { EsimEntitlement.parse("<!DOCTYPE doc [<!ENTITY x SYSTEM 'file:///private'>]><doc/>") }
        assertEquals(48537, assertThrows(EsimFailure::class.java) { EsimEntitlement.response(mapOf("OperationResult" to "1", "SubscriptionResult" to "2", "ProfileActivationCode" to "bad")) }.status)
        assertEquals(48538, assertThrows(EsimFailure::class.java) { EsimEntitlement.response(mapOf("OperationResult" to "0")) }.status)
    }
    @Test fun binderChecksRealPermissionsBeforeDispatch() {
        val binder = EsimTransferBinder(context, Binder.getCallingUid(), permission = { throw SecurityException("fixture") })
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { data.writeInterfaceToken(EsimTransferBinder.DESCRIPTOR)
            assertTrue(binder.transact(12, data, reply, 0))
            reply.setDataPosition(0); assertThrows(SecurityException::class.java) { reply.readException() } }
        finally { data.recycle(); reply.recycle(); binder.close() }
    }
}
