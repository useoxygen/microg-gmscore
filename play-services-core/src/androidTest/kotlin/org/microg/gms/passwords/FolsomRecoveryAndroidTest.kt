/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.auth.folsom.RecoveryRequest
import com.google.android.gms.auth.folsom.RecoveryResult
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalSyncStatusCallback
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.folsom.FolsomRecoveryInput
import java.security.MessageDigest

/** Source-shaped parcel/ownership fixtures only: never opens a vault, account, activity or network. */
@RunWith(AndroidJUnit4::class)
class FolsomRecoveryAndroidTest {
    private val hash = ByteArray(32) { (it + 1).toByte() }
    private val parameters = Proto.field(4, byteArrayOf(1) + ByteArray(16) { 7 })
    private val metadata = Proto.integer(1, 1)
    private val challenge = byteArrayOf(19, 29, 37)
    private fun request() = RecoveryRequest("synthetic@example.invalid", "com.google", hash, parameters, challenge, metadata)

    /** Write literal SafeParcel field IDs without using either production creator/writer. */
    private fun sourceParcel(fields: (Parcel) -> Unit): Parcel = Parcel.obtain().also { p ->
        p.writeInt(0xffff4f45.toInt()); p.writeInt(0)
        fields(p)
        val end = p.dataPosition(); p.setDataPosition(4); p.writeInt(end - 8); p.setDataPosition(0)
    }
    private fun field(p: Parcel, id: Int, body: () -> Unit) {
        p.writeInt(0xffff0000.toInt() or id); p.writeInt(0)
        val start = p.dataPosition(); body(); val end = p.dataPosition()
        p.setDataPosition(start - 4); p.writeInt(end - start); p.setDataPosition(end)
    }
    @Test fun sourceRecoveryRequestSixFieldsReachTheirActualSdkConsumers() {
        val p = sourceParcel { parcel ->
            field(parcel, 6) { parcel.writeByteArray(metadata) }
            field(parcel, 99) { parcel.writeString("unknown future field") }
            field(parcel, 2) { parcel.writeString("com.google") }
            field(parcel, 5) { parcel.writeByteArray(challenge) }
            field(parcel, 1) { parcel.writeString("synthetic@example.invalid") }
            field(parcel, 4) { parcel.writeByteArray(parameters) }
            field(parcel, 3) { parcel.writeByteArray(hash) }
        }
        try {
            val decoded = RecoveryRequest.CREATOR.createFromParcel(p)
            assertEquals(request(), decoded)
            FolsomRecoveryInput(decoded).use { input ->
                assertEquals(decoded.accountName, input.accountName)
                assertArrayEquals(hash, input.ownedHash())
                assertArrayEquals(challenge, input.challenge)
            }
            assertFalse(decoded.toString().contains("synthetic"))
        } finally { p.recycle() }
        val outgoing = Parcel.obtain()
        try {
            request().writeToParcel(outgoing, 0); outgoing.setDataPosition(0)
            val end = SafeParcelReader.readObjectHeader(outgoing)
            val seen = HashSet<Int>()
            while (outgoing.dataPosition() < end) {
                val header = SafeParcelReader.readHeader(outgoing)
                val id = SafeParcelReader.getFieldId(header); assertTrue(seen.add(id))
                when (id) {
                    1 -> assertEquals("synthetic@example.invalid", SafeParcelReader.readString(outgoing, header))
                    2 -> assertEquals("com.google", SafeParcelReader.readString(outgoing, header))
                    3 -> assertArrayEquals(hash, SafeParcelReader.readByteArray(outgoing, header))
                    4 -> assertArrayEquals(parameters, SafeParcelReader.readByteArray(outgoing, header))
                    5 -> assertArrayEquals(challenge, SafeParcelReader.readByteArray(outgoing, header))
                    6 -> assertArrayEquals(metadata, SafeParcelReader.readByteArray(outgoing, header))
                    else -> fail("Unexpected recovery field")
                }
            }
            assertEquals(setOf(1, 2, 3, 4, 5, 6), seen)
        } finally { outgoing.recycle() }
    }
    @Test fun sourceRecoveryResultPreservesActualCodeAndUnconvertedRetryValue() {
        val p = sourceParcel { parcel ->
            field(parcel, 2) { parcel.writeLong(1_234_567_890_123L) }
            field(parcel, 1) { parcel.writeInt(6) }
        }
        try {
            val result = RecoveryResult.CREATOR.createFromParcel(p)
            assertEquals(6, result.result); assertEquals(1_234_567_890_123L, result.retryAfter)
            assertEquals(RecoveryResult(6, 1_234_567_890_123L), result)
            assertEquals("RecoveryResult[redacted]", result.toString())
        } finally { p.recycle() }
    }
    @Test fun sdkMutableArraysCannotReplaceTheProofOrScopeAfterBinderReturns() {
        val original = request()
        val input = FolsomRecoveryInput(original)
        original.secretHash.fill(0); original.vaultParameters.fill(0); original.vaultMetadata.fill(0); original.vaultChallenge.fill(0)
        assertArrayEquals(hash, input.ownedHash())
        assertArrayEquals(challenge, input.challenge)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(Proto.field(1, parameters) + Proto.field(2, metadata)), input.scopeDigest)
        val owned = input.ownedHash(); owned.fill(0)
        assertArrayEquals(hash, input.ownedHash())
        input.close(); input.close()
        assertThrows(IllegalStateException::class.java) { input.ownedHash() }
        assertEquals("FolsomRecoveryInput[redacted]", input.toString())
    }
    @Test fun malformedProofOrAmbiguousScopeNeverReachesRecovery() {
        val invalid = listOf(
            request().apply { secretHash = ByteArray(31) },
            request().apply { accountType = "other" },
            request().apply { vaultChallenge = byteArrayOf() },
            request().apply { vaultParameters = parameters + parameters },
            request().apply { vaultMetadata = Proto.integer(1, 4) },
            request().apply { vaultParameters = Proto.field(4, byteArrayOf(2) + ByteArray(16)) },
            request().apply { secretHash = null }
        )
        invalid.forEach { value -> assertThrows(Exception::class.java) { FolsomRecoveryInput(value) } }
        FolsomRecoveryInput(request().apply { accountType = "" }).close()
        FolsomRecoveryInput(request().apply { vaultParameters = Proto.field(4, byteArrayOf(3) + ByteArray(16)) }).close()
        assertArrayEquals(hash, request().secretHash)
    }
    @Test fun sourceSyncStatusCallbackRetainsAllIntegerStatesInsteadOfCollapsingToBoolean() {
        var actual = -1
        val callback = object : IKeyRetrievalSyncStatusCallback.Stub() {
            override fun onResult(status: Status?, syncStatus: Int) { assertTrue(status!!.isSuccess); actual = syncStatus }
        }
        for (sourceState in 1..7) {
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try {
                request.writeInterfaceToken("com.google.android.gms.auth.folsom.internal.IKeyRetrievalSyncStatusCallback")
                request.writeInt(1); Status.SUCCESS.writeToParcel(request, 0); request.writeInt(sourceState)
                assertTrue(callback.asBinder().transact(1, request, response, 0))
                response.readException(); assertEquals(sourceState, actual)
            } finally { request.recycle(); response.recycle() }
        }
    }
}
