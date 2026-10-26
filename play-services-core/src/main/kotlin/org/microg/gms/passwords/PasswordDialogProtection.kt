/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.Build
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

internal fun rejectsPasswordTouch(flags: Int): Boolean = flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
    (Build.VERSION.SDK_INT >= 29 && flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)

/** Dialogs have their own Window callback; the Activity's dispatchTouchEvent never sees these taps. */
internal class PasswordDialogTouchGuard(private val callback: Window.Callback) : Window.Callback by callback {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (rejectsPasswordTouch(event.flags)) false else callback.dispatchTouchEvent(event)

    // Kotlin interface delegation skips Java default methods. Forward both explicitly;
    // Android introduces these framework callbacks only at their annotated API levels.
    @RequiresApi(24)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        callback.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    @RequiresApi(26)
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        callback.onPointerCaptureChanged(hasCapture)
    }
}

@Composable
internal fun PasswordDialogProtection() {
    val view = LocalView.current
    DisposableEffect(view) {
        var current: View? = view
        var window: Window? = null
        while (current != null && window == null) {
            window = (current as? DialogWindowProvider)?.window
            current = current.parent as? View
        }
        val ownerWindow = window ?: error("Password dialog window unavailable")
        ownerWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val previous = ownerWindow.callback
        val guard = PasswordDialogTouchGuard(previous)
        ownerWindow.callback = guard
        onDispose { if (ownerWindow.callback === guard) ownerWindow.callback = previous }
    }
}
