/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.view.InputDevice
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PasswordDialogProtectionTest {
    @Test fun publicWindowCallbackGuardForwardsEveryOtherCallbackIncludingDefaultMethods() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        val view = View(context)
        val menu = inertInterface(Menu::class.java) as Menu
        val mode = object : ActionMode() {
            override fun setTitle(value: CharSequence?) = Unit
            override fun setTitle(value: Int) = Unit
            override fun setSubtitle(value: CharSequence?) = Unit
            override fun setSubtitle(value: Int) = Unit
            override fun setCustomView(value: View?) = Unit
            override fun invalidate() = Unit
            override fun finish() = Unit
            override fun getMenu(): Menu = menu
            override fun getTitle(): CharSequence = "public-title"
            override fun getSubtitle(): CharSequence = "public-subtitle"
            override fun getCustomView(): View = view
            override fun getMenuInflater(): MenuInflater = MenuInflater(context)
        }
        val delivered = ArrayList<Pair<Method, List<Any?>>>()
        val callback = callback { method, arguments ->
            delivered += method to arguments
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> true
                View::class.java -> view
                ActionMode::class.java -> mode
                else -> null
            }
        }
        val guard = PasswordDialogTouchGuard(callback)
        val methods = Window.Callback::class.java.methods.filter { it.declaringClass == Window.Callback::class.java && it.name != "dispatchTouchEvent" }
        assertTrue(methods.any { it.name == "onProvideKeyboardShortcuts" })
        assertTrue(methods.any { it.name == "onPointerCaptureChanged" })
        for (method in methods) {
            val arguments = method.parameterTypes.map { type -> when (type) {
                Int::class.javaPrimitiveType -> 73
                Boolean::class.javaPrimitiveType -> true
                List::class.java -> ArrayList<Any>()
                View::class.java -> view
                ActionMode::class.java -> mode
                KeyEvent::class.java -> KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
                WindowManager.LayoutParams::class.java -> WindowManager.LayoutParams()
                AccessibilityEvent::class.java -> AccessibilityEvent.obtain()
                SearchEvent::class.java -> SearchEvent::class.java.getConstructor(InputDevice::class.java)
                    .newInstance(*arrayOfNulls<Any>(1))
                else -> if (type.isInterface) inertInterface(type) else error("Unmapped public callback argument: $type")
            } }.toTypedArray()
            delivered.clear()
            val result = method.invoke(guard, *arguments)
            assertEquals(method.toString(), 1, delivered.size)
            assertEquals(method, delivered.single().first)
            assertEquals(arguments.toList(), delivered.single().second)
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> assertEquals(true, result)
                View::class.java -> assertSame(view, result)
                ActionMode::class.java -> assertSame(mode, result)
                else -> assertNull(result)
            }
        }
    }

    @Test fun actualTouchCallbackRefusesBothObscurationFlagsBeforeCallingItsDelegate() {
        var delivered = 0
        val guard = PasswordDialogTouchGuard(callback { method, _ ->
            check(method.name == "dispatchTouchEvent")
            delivered++
            true
        })
        for (flags in listOf(0, MotionEvent.FLAG_WINDOW_IS_OBSCURED, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, arrayOf(pointer), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            try { assertEquals(flags == 0, guard.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
        assertEquals(1, delivered)
    }

    private fun callback(action: (Method, List<Any?>) -> Any?): Window.Callback = Proxy.newProxyInstance(
        Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java),
    ) { _, method, arguments -> action(method, arguments?.toList().orEmpty()) } as Window.Callback

    private fun inertInterface(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === arguments?.singleOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PublicCallbackFixture"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
    }
}
