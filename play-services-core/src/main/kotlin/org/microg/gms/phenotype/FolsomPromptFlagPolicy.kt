/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

/** Pinned jbko/jblg/jblu DeviceFlags. These defaults are not Google server rollout assignments. */
internal data class FolsomPromptFlags(val skipConsent: Boolean, val notificationsEnabled: Boolean, val notificationDelayMillis: Long) {
    val promptEnabled: Boolean get() = !skipConsent && notificationsEnabled
}

internal object FolsomPromptFlagPolicy {
    const val NAMESPACE = "com.google.android.gms.auth_folsom"
    const val SKIP_CONSENT = "KeychainConsent__skip_consent"
    const val ENABLE_NOTIFICATIONS = "ZuulMvp__enable_notifications"
    const val NOTIFICATION_DELAY = "ZuulMvp__lskf_notification_delay"
    const val DEFAULT_NOTIFICATION_DELAY = 86_400_000L
    // Phenotype Flag.DATA_TYPE_LONG / DATA_TYPE_BOOL, mirrored without an Android dependency.
    val types: Map<String, Int> = linkedMapOf(SKIP_CONSENT to 2, ENABLE_NOTIFICATIONS to 2, NOTIFICATION_DELAY to 1)

    fun resolve(namespace: String, values: Map<String, *>): FolsomPromptFlags {
        require(namespace == NAMESPACE) { "Unsupported flag namespace" }
        types.keys.filter(values::containsKey).forEach { checkedValue(it, values[it]) }
        return FolsomPromptFlags(values[SKIP_CONSENT] as? Boolean ?: false,
            values[ENABLE_NOTIFICATIONS] as? Boolean ?: false, values[NOTIFICATION_DELAY] as? Long ?: DEFAULT_NOTIFICATION_DELAY)
    }

    fun checkedValue(name: String, value: Any?): Any = when (types[name]) {
        1 -> value as? Long ?: throw IllegalArgumentException("Flag must be an int64")
        2 -> value as? Boolean ?: throw IllegalArgumentException("Flag must be a boolean")
        else -> throw IllegalArgumentException("Unsupported flag")
    }

    fun providerValue(name: String, value: String): Any = when (types[name]) {
        1 -> value.toLongOrNull() ?: throw IllegalArgumentException("Flag must be an int64")
        2 -> when (value) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("Flag must be a boolean") }
        else -> throw IllegalArgumentException("Unsupported flag")
    }

    /** Actual provider mutations call this with the platform Binder UID, never a claimed package. */
    fun requireLocalWriter(callerUid: Int, ownerUid: Int) {
        if (callerUid != ownerUid) throw SecurityException("Local flag overrides require the Services UID")
    }
}
