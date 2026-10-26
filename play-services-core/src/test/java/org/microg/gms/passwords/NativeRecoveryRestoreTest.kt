/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.android.gms.auth.folsom.RecoveryRequest
import com.google.android.gms.auth.folsom.RecoveryResult
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.vault.VaultListRequest
import org.microg.gms.auth.folsom.vault.VaultOpenRequest
import org.microg.gms.auth.folsom.vault.VaultOpenResponse

class NativeRecoveryRestoreTest {
    @Test fun generatedOpenRequestRoundTripsTheExactSourceFieldsAndDoesNotAcceptAHashFieldAsClaim() {
        val raw = Proto.field(2, ByteArray(17) { 1 }) + Proto.field(3, byteArrayOf(2)) + Proto.field(4, byteArrayOf(3)) +
            Proto.integer(5, 1) + Proto.integer(8, 3) + Proto.field(9, RecoverableVaultWire.context("session", 123))
        val parsed = VaultOpenRequest.ADAPTER.decode(raw)
        assertEquals(1, parsed.secretType); assertEquals(3, parsed.source); assertEquals("session", parsed.context!!.session)
        assertArrayEquals(raw, parsed.encode())
        assertEquals(4, VaultListRequest.ADAPTER.decode(RecoverableVaultWire.list("s", 123, source = 4)).source)
    }
    @Test fun typedSdkFieldsAndOpenResultCarryOnlyTheirActualSourceMeaning() {
        val request = RecoveryRequest()
        request.accountName = "synthetic"; request.accountType = "com.google"
        request.secretHash = ByteArray(32) { 1 }; request.vaultParameters = byteArrayOf(2)
        request.vaultChallenge = byteArrayOf(3); request.vaultMetadata = byteArrayOf(4)
        assertEquals("synthetic", request.accountName); assertEquals(32, request.secretHash.size)
        val result = RecoveryResult(); result.result = 2; result.retryAfter = 123
        assertEquals(2, result.result); assertEquals(123L, result.retryAfter)
        val response = VaultOpenResponse.ADAPTER.decode(Proto.integer(1, 2))
        assertEquals(2, response.result); assertNull(response.vault)
    }
    @Test fun cachedConfirmedAttemptForAnotherSdkBackupCannotAcknowledgeTheRequestedScope() {
        val expected = ByteArray(32) { 1 }; val other = ByteArray(32) { 2 }
        assertFalse(RecoveryRestoreStatus(RecoveryRestorePhase.CONFIRMED, "other", 1, other).acknowledges(expected))
        assertFalse(RecoveryRestoreStatus(RecoveryRestorePhase.RECOVERED, "same", 1, expected).acknowledges(expected))
        assertFalse(RecoveryRestoreStatus(RecoveryRestorePhase.CONFIRMED, "missing", 1).acknowledges(expected))
        assertTrue(RecoveryRestoreStatus(RecoveryRestorePhase.CONFIRMED, "same", 1, expected.copyOf()).acknowledges(expected))
    }
    @Test fun sdkNeverReceivesGuessValidBeforeTheActualRecoveredKeysCommit() {
        for (phase in listOf(RecoveryRestorePhase.LAUNCHED, RecoveryRestorePhase.OPENED,
            RecoveryRestorePhase.IMPORT_LAUNCHED, RecoveryRestorePhase.RECOVERED, RecoveryRestorePhase.REVOKED))
            assertNull(RecoveryRestoreStatus(phase, "attempt", 1).sdkResultCode)
        assertEquals(1, RecoveryRestoreStatus(RecoveryRestorePhase.CONFIRMED, "attempt", 1).sdkResultCode)
        assertEquals(6, RecoveryRestoreStatus(RecoveryRestorePhase.REFUSED, "attempt", 9, retryAfter = 27).sdkResultCode)
        assertEquals(27L, RecoveryRestoreStatus(RecoveryRestorePhase.REFUSED, "attempt", 9, retryAfter = 27).retryAfter)
        assertEquals(0, RecoveryRestoreStatus(RecoveryRestorePhase.REFUSED, "attempt", 10).sdkResultCode)
    }
    @Test fun onlyActualOpenNetworkFailuresHaveTheSourceNetworkResultAndGrpcAuthNeverFallsThroughIOException() {
        for (failure in listOf(java.io.IOException("synthetic transport loss"), GrpcException(GrpcStatus.UNAVAILABLE, null),
            GrpcException(GrpcStatus.DEADLINE_EXCEEDED, null))) {
            val actual = assertThrows(RecoveryRestoreFailure::class.java) { RecoveryRestoreNetwork.execute<ByteArray> { throw failure } }
            assertEquals(9, actual.sdkResult)
        }
        for (failure in listOf(GrpcException(GrpcStatus.UNAUTHENTICATED, null), GrpcException(GrpcStatus.PERMISSION_DENIED, null),
            GrpcException(GrpcStatus.INVALID_ARGUMENT, null), ProtocolException(), VaultLockedException())) {
            val actual = assertThrows(Exception::class.java) { RecoveryRestoreNetwork.execute<ByteArray> { throw failure } }
            assertSame(failure, actual)
        }
        assertEquals(7, RecoveryRestoreNetwork.execute { 7 })
    }
}
