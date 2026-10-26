/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.FolsomAccountFence
import org.microg.gms.auth.folsom.FolsomCallerAdmission
import org.microg.gms.auth.folsom.FolsomCallerIdentity

class FolsomCallerAdmissionTest {
    private val serviceUid = 1000
    private fun caller(uid: Int = 2000, name: String = "com.android.chrome", certificate: String = "1".repeat(64), trusted: Boolean = true) =
        FolsomCallerIdentity(uid, name, certificate, trusted)

    @Test fun externalAdmissionRequiresExactChromeCurrentSignerPermissionAndChromesyncDomain() {
        for (name in listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary"))
            assertTrue(FolsomCallerAdmission.allowed(caller(name = name), "chromesync", serviceUid))
        for (name in listOf("com.android.chrome.lookalike", "com.google.android.chrome", "com.google.android.apps.maps", "arbitrary.fixture"))
            assertFalse(FolsomCallerAdmission.allowed(caller(name = name), "chromesync", serviceUid))
        assertFalse(FolsomCallerAdmission.allowed(caller(trusted = false), "chromesync", serviceUid))
        assertFalse(FolsomCallerAdmission.allowed(caller(certificate = "invalid"), "chromesync", serviceUid))
        for (domain in listOf("gpm_passkeys", "passwords", "hw_protected", "users/me/securitydomains/chromesync", "chromesync.other"))
            assertFalse(FolsomCallerAdmission.allowed(caller(), domain, serviceUid))
        assertTrue(FolsomCallerAdmission.allowed(caller(uid = serviceUid, name = "internal.fixture", trusted = false), "gpm_passkeys", serviceUid))
        assertFalse(FolsomCallerAdmission.allowed(caller(uid = serviceUid), "invalid/domain", serviceUid))
    }
    @Test fun aTransferredBinderCannotAdoptAnotherUidEvenIfThatUidHasItsOwnTrustedPackage() {
        val captured = caller()
        val guard = FolsomCallerAdmission(captured, "chromesync", serviceUid)
        guard.checkTransaction(2000, caller())
        assertThrows(SecurityException::class.java) { guard.checkTransaction(3000, caller(uid = 3000)) }
        assertThrows(SecurityException::class.java) { guard.checkTransaction(3000, caller()) }
        assertThrows(SecurityException::class.java) { guard.checkTransaction(2000, caller(name = "com.chrome.beta")) }
    }
    @Test fun uninstallSignerReplacementAndPermissionRevocationCancelAnAlreadyCreatedConnection() {
        val guard = FolsomCallerAdmission(caller(), "chromesync", serviceUid)
        for (changed in listOf(null, caller(uid = 3000), caller(certificate = "2".repeat(64)), caller(trusted = false))) {
            var released = false
            assertThrows(SecurityException::class.java) { guard.checkCurrent(changed); released = true }
            assertFalse(released)
        }
        guard.checkCurrent(caller())
    }
    @Test fun accountRemovalOrSameNameReplacementDuringIoCannotReleaseKeyBytes() {
        for (replacement in listOf(null, "replacement-fixture-id")) {
            var current: String? = "original-fixture-id"
            val fence = FolsomAccountFence("fixture-account", "original-fixture-id") { current }
            var released = false
            assertThrows(SecurityException::class.java) {
                val material = fence.checked { current = replacement; byteArrayOf(1, 2, 3) }
                fence.check(); released = material.isNotEmpty()
            }
            assertFalse(released)
        }
    }
    @Test fun accountAndCallerAreCheckedAgainAtTheFinalReleaseBoundary() {
        var current: String? = "original-fixture-id"
        val fence = FolsomAccountFence("fixture-account", "original-fixture-id") { current }
        val guard = FolsomCallerAdmission(caller(), "chromesync", serviceUid)
        val material = fence.checked { byteArrayOf(1, 2, 3) }
        current = null
        var released = false
        assertThrows(SecurityException::class.java) { guard.checkCurrent(caller()); fence.check(); released = material.isNotEmpty() }
        assertFalse(released)
        assertThrows(SecurityException::class.java) { guard.checkCurrent(caller(certificate = "2".repeat(64))) }
    }
}
