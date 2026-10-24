/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class PasskeyReauthPolicyTest {
    private fun <T> immediate(action: suspend () -> T): T {
        var outcome: Result<T>? = null
        action.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return outcome!!.getOrThrow()
    }
    @Test fun selectedAccountAndLookupAreEncodedOnlyInExactGoogleXreauth() {
        val policy = PasskeyReauthPolicy("stable-owner")
        val target = URI(policy.target("synthetic+owner@example.invalid", "synthetic&lookup=#"))
        assertEquals("https", target.scheme); assertEquals("accounts.google.com", target.host)
        assertEquals("/embedded/xreauth/android", target.path)
        assertTrue(target.rawQuery.contains("Email=synthetic%2Bowner%40example.invalid"))
        assertTrue(target.rawQuery.contains("plt=synthetic%26lookup%3D%23"))
        assertNull(target.fragment)
        assertEquals("synthetic-proof", policy.result("https://accounts.google.com", true, "stable-owner", 1, "synthetic-proof"))
        for (origin in listOf("http://accounts.google.com", "https://accounts.google.com.evil.invalid", "https://evil.invalid@accounts.google.com", "https://accounts.google.com:444", "https://accounts.google.com/path"))
            assertThrows(VaultLockedException::class.java) { policy.result(origin, true, "stable-owner", 1, "synthetic-proof") }
        assertThrows(VaultLockedException::class.java) { policy.result("https://accounts.google.com", false, "stable-owner", 1, "synthetic-proof") }
        assertThrows(VaultLockedException::class.java) { policy.result("https://accounts.google.com", true, "same-name-new-account", 1, "synthetic-proof") }
        assertThrows(VaultLockedException::class.java) { policy.result("https://accounts.google.com", true, "stable-owner", 2, "synthetic-proof") }
        assertThrows(VaultLockedException::class.java) { policy.result("https://accounts.google.com", true, "stable-owner", 1, "") }
    }
    @Test fun actualLookupTokenSurvivesProtocolParsingButNeverDiagnostics() {
        val exception = assertThrows(PasskeyReauthRequiredException::class.java) {
            PasskeyDeviceAuthorization.response(Proto.field(2, Proto.field(1, "synthetic-private-lookup")), setOf(7))
        }
        assertEquals("synthetic-private-lookup", exception.packagedLookupToken)
        assertFalse(exception.toString().contains("synthetic-private-lookup"))
        assertFalse(exception.message.orEmpty().contains("synthetic-private-lookup"))
        for (challenge in listOf(byteArrayOf(), Proto.field(1, ""), Proto.field(1, "one") + Proto.field(1, "two")))
            assertThrows(ProtocolException::class.java) { PasskeyDeviceAuthorization.response(Proto.field(2, challenge), setOf(7)) }
    }
    @Test fun singleOwnerReauthRetriesDeviceRequestWithFreshSignalsAndRealRaptField() {
        var requests = 0; var prompts = 0
        val signals = ArrayList<Map<String, String>>()
        val value = immediate { PasskeyReauthRetry.run(operation = { proof ->
            requests++
            val salt = ByteArray(16) { requests.toByte() }
            signals.add(PasskeyDeviceAuthorization.signals(salt, true, proof))
            val request = Proto.decode(PasskeyDeviceAuthorization.request(salt, true, byteArrayOf(requests.toByte()), proof))
            if (requests == 1) {
                assertNull(request.optional(1))
                throw PasskeyReauthRequiredException("synthetic-lookup")
            }
            assertEquals("synthetic-proof", request.string(1))
            assertArrayEquals(byteArrayOf(2), Proto.decode(request.one(3)).one(1))
            "native-result"
        }, reauthenticate = { lookup -> prompts++; assertEquals("synthetic-lookup", lookup); "synthetic-proof" }) }
        assertEquals("native-result", value); assertEquals(2, requests); assertEquals(1, prompts)
        assertNotEquals(signals[0]["salt"], signals[1]["salt"])
        assertFalse(signals[0].containsKey("rapt_token")); assertTrue(signals[1].containsKey("rapt_token"))
        requests = 0; prompts = 0
        assertThrows(PasskeyReauthRequiredException::class.java) { immediate {
            PasskeyReauthRetry.run<String>({ requests++; throw PasskeyReauthRequiredException("synthetic") }, { prompts++; "synthetic-proof" })
        } }
        assertEquals(2, requests); assertEquals(1, prompts)
        requests = 0
        assertThrows(VaultLockedException::class.java) { immediate {
            PasskeyReauthRetry.run<String>({ requests++; throw PasskeyReauthRequiredException("synthetic") }, { throw VaultLockedException() })
        } }
        assertEquals(1, requests)
        requests = 0; prompts = 0
        assertThrows(PasswordRpcException::class.java) { immediate {
            PasskeyReauthRetry.run<String>({ requests++; throw PasswordRpcException(503, true) }, { prompts++; "synthetic-proof" })
        } }
        assertEquals(1, requests); assertEquals(0, prompts) // uncertain mutations are never replayed
    }
}
