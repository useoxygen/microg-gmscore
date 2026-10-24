/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the actual owner activity boundary; a lower reported SDK must never become no-lock presence. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PasswordOwnerPlatformTest {
    class OwnerFixture : PasswordOwnerActivity() {
        var authenticated = 0
        var refused = 0
        fun requestOwner() = authenticateOwner()
        fun checkOwner() = requireOwner()
        fun pauseOwner() = onPause()
        override fun onOwnerAuthenticated() { authenticated++ }
        override fun onOwnerAuthenticationFailed() { refused++ }
        override fun onOwnerLocked() = Unit
    }

    @Test fun unsupportedSdkRefusesInsteadOfAcceptingNoLockPresence() {
        val controller = Robolectric.buildActivity(OwnerFixture::class.java)
        val activity = controller.get()
        val sdk = Build.VERSION.SDK_INT
        try {
            // Keep the supported framework sandbox, but drive the real activity's unsupported-SDK boundary.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 22)
            activity.requestOwner()
            assertEquals(0, activity.authenticated)
            assertEquals(1, activity.refused)
            assertThrows(VaultLockedException::class.java) { activity.checkOwner() }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @Test fun ownerPauseRevokesSupportedNoLockPresence() {
        val controller = Robolectric.buildActivity(OwnerFixture::class.java)
        val activity = controller.get()
        // Exercise the actual authority methods without AppCompat UI resources or a fabricated lifecycle claim.
        ReflectionHelpers.setField(activity, "foreground", true)
        activity.requestOwner()
        assertEquals(1, activity.authenticated)
        assertEquals(0, activity.refused)
        activity.checkOwner()
        activity.pauseOwner()
        assertThrows(VaultLockedException::class.java) { activity.checkOwner() }
    }
}
