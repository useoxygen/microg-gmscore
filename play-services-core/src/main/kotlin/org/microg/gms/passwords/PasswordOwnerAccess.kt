/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** UI access policy only. No-lock presence is not device-credential authentication. */
internal class PasswordOwnerAccess {
    private enum class Challenge { NO_LOCK, DEVICE_CREDENTIAL }
    private var pending: Challenge? = null
    private var granted: Challenge? = null

    fun begin(deviceSecure: Boolean) {
        granted = null
        pending = if (deviceSecure) Challenge.DEVICE_CREDENTIAL else Challenge.NO_LOCK
    }

    fun complete(credentialConfirmed: Boolean, deviceSecure: Boolean, deviceUnlocked: Boolean, foreground: Boolean): Boolean {
        val challenge = pending
        pending = null
        granted = challenge?.takeIf {
            foreground && deviceUnlocked && deviceSecure == (it == Challenge.DEVICE_CREDENTIAL) &&
                (it == Challenge.NO_LOCK || credentialConfirmed)
        }
        return granted != null
    }

    fun allows(deviceSecure: Boolean, deviceUnlocked: Boolean, foreground: Boolean): Boolean =
        granted?.let { foreground && deviceUnlocked && deviceSecure == (it == Challenge.DEVICE_CREDENTIAL) } ?: false

    fun cancel() { pending = null; granted = null }

    fun stopped() {
        granted = null
        // The system credential challenge itself stops the calling activity.
        if (pending == Challenge.NO_LOCK) pending = null
    }
}
