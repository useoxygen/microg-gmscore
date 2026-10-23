/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class LegacyPasskeyBridgePolicyTest {
    private val google = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun browserOriginRequiresTheActualReleaseSignerAndExactHttpsOrigin() {
        assertTrue(LegacyPasskeyBridgePolicy.browser("com.android.chrome", google, "https://login.example.invalid"))
        assertTrue(LegacyPasskeyBridgePolicy.browser("com.chrome.beta", google, "https://login.example.invalid:8443"))
        for (pkg in listOf("com.android.chrome.evil", "com.example.browser", "com.google.android.gms"))
            assertFalse(LegacyPasskeyBridgePolicy.browser(pkg, google, "https://login.example.invalid"))
        assertFalse(LegacyPasskeyBridgePolicy.browser("com.android.chrome", google.copyOf().apply { this[0] = 0 }, "https://login.example.invalid"))
        for (origin in listOf("http://login.example.invalid", "https://user@login.example.invalid", "https://login.example.invalid/path",
                "https://login.example.invalid?origin=other", "https://login.example.invalid#fragment", "https://login.example.invalid:99999",
                "android:apk-key-hash:synthetic", "https://"))
            assertFalse(origin, LegacyPasskeyBridgePolicy.browser("com.android.chrome", google, origin))
    }
    @Test fun requestDigestBindsActualCallerSignerSourceOriginOperationAndEveryOptionByte() {
        fun digest(pkg: String = "com.example.app", signer: ByteArray = google, source: String = "app",
            origin: String = "android:apk-key-hash:synthetic", create: Boolean = true, options: ByteArray = byteArrayOf(1, 2)) =
            LegacyPasskeyBridgePolicy.requestDigest(pkg, signer, source, origin, create, options)
        val expected = digest()
        assertArrayEquals(expected, digest())
        for (altered in listOf(digest(pkg = "com.example.other"), digest(signer = google.copyOf().apply { this[0] = 0 }),
                digest(source = "browser"), digest(origin = "https://example.invalid"), digest(create = false), digest(options = byteArrayOf(1, 3))))
            assertFalse(expected.contentEquals(altered))
        assertThrows(ProtocolException::class.java) { digest(source = "hybrid") }
        assertThrows(ProtocolException::class.java) { digest(signer = byteArrayOf(1)) }
        assertThrows(ProtocolException::class.java) { digest(options = byteArrayOf()) }
        assertThrows(ProtocolException::class.java) { digest(options = ByteArray(512 * 1024 + 1)) }
    }
    @Test fun onlyActualSingletonSystemServicesUidCanIssueOrRedeemNativeTickets() {
        assertTrue(LegacyPasskeyBridgePolicy.systemCaller(10042, 10042, true, setOf("com.google.android.gms")))
        assertFalse(LegacyPasskeyBridgePolicy.systemCaller(10042, 10043, true, setOf("com.google.android.gms")))
        assertFalse(LegacyPasskeyBridgePolicy.systemCaller(10042, 10042, false, setOf("com.google.android.gms")))
        assertFalse(LegacyPasskeyBridgePolicy.systemCaller(10042, 10042, true, setOf("com.google.android.gms", "com.example.shared")))
        assertFalse(LegacyPasskeyBridgePolicy.systemCaller(10042, 10042, true, setOf("com.example.app")))
    }
    @Test fun unsupportedSyncedOptionsStayAvailableOnlyToExistingLocalOrSecurityKeyConsumers() {
        fun create(algorithms: List<Int> = listOf(-7), attestation: String? = null, attachment: String? = null,
            extensions: Set<String> = emptySet()) = LegacyPasskeyBridgePolicy.supported(true, algorithms, attestation, attachment, extensions)
        assertTrue(create()); assertTrue(create(algorithms = listOf(-257, -7), attestation = "none", attachment = "platform"))
        assertFalse(create(algorithms = listOf(-257))); assertFalse(create(algorithms = emptyList()))
        for (attestation in listOf("direct", "indirect", "enterprise")) assertFalse(create(attestation = attestation))
        assertFalse(create(attachment = "cross-platform"))
        assertTrue(create(extensions = setOf("prf")))
        assertTrue(LegacyPasskeyBridgePolicy.supported(false, emptyList(), null, null, setOf("prf")))
        for (extension in listOf("hmac-secret", "appid", "largeBlob", "credBlob", "uvm", "payment", "devicePubKey", "unknown", "credProps")) {
            assertFalse(create(extensions = setOf(extension)))
            assertFalse(LegacyPasskeyBridgePolicy.supported(false, emptyList(), null, null, setOf(extension)))
        }
        assertTrue(LegacyPasskeyBridgePolicy.supported(false, emptyList(), null, null, emptySet()))
    }
    @Test fun ownerGrantExpiresOnBackgroundLockFocusLossGenerationChangeOrDeadline() {
        fun allowed(now: Long = 1_000, generation: Boolean = true, resumed: Boolean = true, focused: Boolean = true,
            secure: Boolean = true, unlocked: Boolean = true, interactive: Boolean = true) =
            LegacyPasskeyBridgePolicy.owner(1_000, now, generation, resumed, focused, secure, unlocked, interactive)
        assertTrue(allowed()); assertTrue(allowed(now = 61_000))
        assertFalse(allowed(now = 61_001)); assertFalse(allowed(now = 999)); assertFalse(allowed(generation = false))
        assertFalse(allowed(resumed = false)); assertFalse(allowed(focused = false)); assertFalse(allowed(secure = false))
        assertFalse(allowed(unlocked = false)); assertFalse(allowed(interactive = false))
    }
}
