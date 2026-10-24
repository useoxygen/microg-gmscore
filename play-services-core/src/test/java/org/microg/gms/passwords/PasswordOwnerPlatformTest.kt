/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
        override fun onCreate(state: Bundle?) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            super.onCreate(state)
        }
        fun requestOwner() = authenticateOwner()
        fun checkOwner() = requireOwner()
        override fun onOwnerAuthenticated() { authenticated++ }
        override fun onOwnerAuthenticationFailed() { refused++ }
        override fun onOwnerLocked() = Unit
    }

    @Test fun unsupportedSdkRefusesInsteadOfAcceptingNoLockPresence() {
        val controller = Robolectric.buildActivity(OwnerFixture::class.java).setup()
        val activity = controller.get()
        val sdk = Build.VERSION.SDK_INT
        try {
            // Keep the supported framework sandbox, but drive the real activity's unsupported-SDK boundary.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 22)
            activity.requestOwner()
            assertEquals(0, activity.authenticated)
            assertEquals(1, activity.refused)
            assertThrows(VaultLockedException::class.java) { activity.checkOwner() }
            assertNotEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
            controller.pause().stop().destroy()
        }
    }

    @Test fun supportedNoLockPresenceExpiresWhenTheActualActivityPauses() {
        val controller = Robolectric.buildActivity(OwnerFixture::class.java).setup()
        val activity = controller.get()
        try {
            activity.requestOwner()
            assertEquals(1, activity.authenticated)
            assertEquals(0, activity.refused)
            activity.checkOwner()
            controller.pause()
            assertThrows(VaultLockedException::class.java) { activity.checkOwner() }
            assertNotEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally { controller.stop().destroy() }
    }
}
