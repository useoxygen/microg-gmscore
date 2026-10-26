/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.utils.FolsomConsentNotificationPolicy
import org.microg.gms.phenotype.FolsomPromptFlagPolicy

class FolsomPromptFlagTest {
    private val namespace = "com.google.android.gms.auth_folsom"
    @Test fun literalPinnedDeviceFlagNamesTypesAndDefaultsDoNotGuessServerExperiments() {
        assertEquals(namespace, FolsomPromptFlagPolicy.NAMESPACE)
        assertEquals(mapOf("KeychainConsent__skip_consent" to 2, "ZuulMvp__enable_notifications" to 2,
            "ZuulMvp__lskf_notification_delay" to 1), FolsomPromptFlagPolicy.types)
        val flags = FolsomPromptFlagPolicy.resolve(namespace, emptyMap<String, Any>())
        assertFalse(flags.skipConsent); assertFalse(flags.notificationsEnabled)
        assertFalse(flags.promptEnabled); assertEquals(86_400_000L, flags.notificationDelayMillis)
    }
    @Test fun actualLocalSnapshotChangesHaveSkipPrecedenceAndSourceStrictConfiguredCooldown() {
        val values = mutableMapOf<String, Any>("ZuulMvp__enable_notifications" to true,
            "ZuulMvp__lskf_notification_delay" to 123L)
        val enabled = FolsomPromptFlagPolicy.resolve(namespace, values)
        assertTrue(enabled.promptEnabled)
        val user = "synthetic-flags-gaia"
        val posted = FolsomConsentNotificationPolicy.posted(AccountData(googleUserId = user), user, "chromesync", 1_000)
        assertFalse(FolsomConsentNotificationPolicy.eligible(posted, user, "chromesync", 1_123, enabled.notificationDelayMillis))
        assertTrue(FolsomConsentNotificationPolicy.eligible(posted, user, "chromesync", 1_124, enabled.notificationDelayMillis))
        values["KeychainConsent__skip_consent"] = true
        assertFalse(FolsomPromptFlagPolicy.resolve(namespace, values).promptEnabled)
        // Preserve the declared signed int64 value instead of inventing rollout bounds/clamps.
        values["ZuulMvp__lskf_notification_delay"] = -1L
        val signed = FolsomPromptFlagPolicy.resolve(namespace, values)
        assertEquals(-1L, signed.notificationDelayMillis)
        assertTrue(FolsomConsentNotificationPolicy.eligible(posted, user, "chromesync", 1_000, signed.notificationDelayMillis))
    }
    @Test fun malformedOrAccountScopedControlsCannotEnablePrompting() {
        for (bad in listOf("true", 1L, 1, null)) {
            assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.resolve(namespace,
                mapOf("ZuulMvp__enable_notifications" to bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.resolve(namespace,
            mapOf("ZuulMvp__enable_notifications" to true, "KeychainConsent__skip_consent" to "false")) }
        assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.resolve(namespace,
            mapOf("ZuulMvp__lskf_notification_delay" to 123)) }
        for (bad in listOf("$namespace#other.package", "$namespace/user", "com.google.android.gms.auth")) {
            assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.resolve(bad,
                mapOf("ZuulMvp__enable_notifications" to true)) }
        }
        assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.checkedValue("unknown_control", true) }
    }
    @Test fun actualOverrideAdmissionRequiresExactServicesUidNotSamePackageClaims() {
        FolsomPromptFlagPolicy.requireLocalWriter(10_123, 10_123)
        for (other in listOf(0, 1_000, 2_000, 10_124, 99_000)) {
            assertThrows(SecurityException::class.java) { FolsomPromptFlagPolicy.requireLocalWriter(other, 10_123) }
        }
    }
    @Test fun actualProviderRowParserPreservesSignedInt64AndRejectsInvalidOrUnknownControls() {
        assertEquals(true, FolsomPromptFlagPolicy.providerValue("ZuulMvp__enable_notifications", "true"))
        assertEquals(false, FolsomPromptFlagPolicy.providerValue("KeychainConsent__skip_consent", "false"))
        for (value in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            assertEquals(value, FolsomPromptFlagPolicy.providerValue("ZuulMvp__lskf_notification_delay", value.toString()))
        }
        for (bad in listOf("yes", "1", "TRUE", "")) {
            assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.providerValue("ZuulMvp__enable_notifications", bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.providerValue("ZuulMvp__lskf_notification_delay", "9223372036854775808") }
        assertThrows(IllegalArgumentException::class.java) { FolsomPromptFlagPolicy.providerValue("unknown", "true") }
    }
}
