/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import com.google.android.gms.BuildConfig

/**
 * The native Google prompt (AuthZen) route is off unless a build sets `-Pcyclon.authzenNative=true`. Nothing at run
 * time can turn it on: no setting, Intent or broadcast reads or writes it.
 *
 * Lab capture (shape-only logging of SecureGcm messages, [ProtoShape]) needs `-Pcyclon.authzenLabCaptureUntil=<epoch
 * millis>`. The build refuses a value more than three days ahead, and capture stops by itself at that time.
 */
internal object AuthZenFeature {
    val enabled: Boolean get() = BuildConfig.CYCLON_AUTHZEN_NATIVE

    fun labCaptureActive(nowWallMillis: Long = System.currentTimeMillis()): Boolean =
        nowWallMillis < BuildConfig.CYCLON_AUTHZEN_LAB_CAPTURE_UNTIL
}

/** Why the native route cannot handle a prompt, in the order they are checked. */
internal enum class AuthZenBlocker {
    FEATURE_OFF,
    /**
     * Android 4.4 (API 19–20) has no `noBackupFilesDir`; the store would fall back to `filesDir`, which ADB backup can
     * copy, so the route does not run there at all.
     */
    NO_PRIVATE_STORAGE,
    /** The owner turned off "Receive two-step verification prompts". */
    OWNER_OPTED_OUT,
    /** No transaction profile has been verified against current Google traffic on a device. */
    PROTOCOL_NOT_VERIFIED,
    /** No symmetric `authzen` key is enrolled for any account (enrollment is not implemented yet). */
    NO_AUTHZEN_KEY,
    NO_SECURE_LOCK,
    NOTIFICATIONS_BLOCKED,
}

internal class AuthZenReadinessInput(
    val featureEnabled: Boolean,
    val ownerOptedIn: Boolean,
    val verifiedProfiles: Int,
    val accountsWithAuthZenKey: Int,
    val secureLock: Boolean,
    val notificationsAllowed: Boolean,
    /** Whether `noBackupFilesDir` exists (API 21+). */
    val privateStorage: Boolean = true,
)

/** The first reason the route is not ready, or null when it could handle a prompt. */
internal fun authZenBlocker(input: AuthZenReadinessInput): AuthZenBlocker? = when {
    !input.featureEnabled -> AuthZenBlocker.FEATURE_OFF
    !input.privateStorage -> AuthZenBlocker.NO_PRIVATE_STORAGE
    !input.ownerOptedIn -> AuthZenBlocker.OWNER_OPTED_OUT
    input.verifiedProfiles == 0 -> AuthZenBlocker.PROTOCOL_NOT_VERIFIED
    input.accountsWithAuthZenKey == 0 -> AuthZenBlocker.NO_AUTHZEN_KEY
    !input.secureLock -> AuthZenBlocker.NO_SECURE_LOCK
    !input.notificationsAllowed -> AuthZenBlocker.NOTIFICATIONS_BLOCKED
    else -> null
}
