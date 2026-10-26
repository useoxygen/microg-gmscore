/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class EnclavePinReauthPolicyTest {
    private val policy = EnclavePinReauthPolicy("synthetic-pin-owner")
    private val callback = EnclavePinReauthPolicy.TARGET + "?rapt=synthetic%2Bproof%2Fvalue%3D"
    @Test fun sourceMainFrameCommitRequiresActualSuccessfulHttpStatusAndReturnsDecodedToken() {
        for (status in listOf(200, 204)) assertEquals("synthetic+proof/value=", policy.result(callback, true, "synthetic-pin-owner", status))
        for (status in listOf(0, 201, 302, 400, 401, 403, 500)) refused { policy.result(callback, true, "synthetic-pin-owner", status) }
        refused { policy.result(callback, false, "synthetic-pin-owner", 200) }
        refused { policy.result(callback, true, "other-owner", 200) }
    }
    @Test fun googleLoginNavigationCannotSupplyAProofAndOriginLookalikesAreRefused() {
        assertTrue(EnclavePinReauthPolicy.navigationAllowed("https://accounts.google.com/ServiceLogin?continue=synthetic"))
        assertFalse(policy.proofCandidate("https://accounts.google.com/?rapt=synthetic"))
        for (url in listOf("http://passwords.google.com/?rapt=x", "https://passwords.google.com.evil.invalid/?rapt=x",
            "https://passwords.google.com@evil.invalid/?rapt=x", "https://evil.invalid@passwords.google.com/?rapt=x",
            "https://passwords.google.com:444/?rapt=x")) {
            assertFalse(EnclavePinReauthPolicy.navigationAllowed(url)); refused { policy.result(url, true, "synthetic-pin-owner", 200) }
        }
    }
    @Test fun ambiguousEmptyOversizedAndFragmentProofsNeverComplete() {
        for (suffix in listOf("?rapt=", "?rapt=x&rapt=y", "?rapt=%0A", "?rapt=" + "x".repeat(16_385), "?rapt=x#rapt=y"))
            refused { policy.result(EnclavePinReauthPolicy.TARGET + suffix, true, "synthetic-pin-owner", 200) }
    }
    private fun refused(action: () -> Unit) { try { action(); fail("Untrusted reauthentication completion") } catch (_: VaultLockedException) { } }
}
