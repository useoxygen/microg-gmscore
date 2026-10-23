/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.R
import com.google.android.gms.credential.manager.EXTRA_KEY_ACCOUNT_NAME
import com.google.android.gms.credential.manager.PasswordManagerActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in owner UI checks; never opens an editor, reveals a password or mutates a login. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PasswordManagerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val open get() = context.getString(R.string.passwords_open)
    private val onDevice get() = context.getString(R.string.passwords_on_device)
    private val touchFlags = CopyOnWriteArrayList<Int>()
    private val touchPoints = CopyOnWriteArrayList<String>()
    private var tapBounds = "unavailable"

    private inner class RecordingCallback(callback: android.view.Window.Callback) : WindowCallbackWrapper(callback) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touchFlags.add(event.flags)
            touchPoints.add("${event.actionMasked}:${event.x},${event.y}")
            return super.dispatchTouchEvent(event)
        }
    }

    private fun selectedAccount(): String {
        assumeTrue(arguments.getString("passwordOwnerUi") == "cyc654-no-lock")
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        assertEquals("UI runner must target the product UI process", "com.google.android.gms:ui", Application.getProcessName())
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("This opt-in suite requires a device without a screen lock", keyguard.isDeviceSecure)
        assertFalse("Dismiss the phone's keyguard before running owner UI checks", keyguard.isKeyguardLocked)
        assertEquals("Designated test account not present", 1,
            AccountManager.get(context).getAccountsByType("com.google").count { it.name == selected })
        return selected!!
    }

    private fun launch(selected: String) = ActivityScenario.launch<PasswordManagerActivity>(
        Intent(context, PasswordManagerActivity::class.java).putExtra(EXTRA_KEY_ACCOUNT_NAME, selected))

    private fun waitForFocus(scenario: ActivityScenario<PasswordManagerActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var focused = false
        var focusedSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                focused = activity.window.decorView.hasWindowFocus()
                if (activity.window.callback !is RecordingCallback)
                    activity.window.callback = RecordingCallback(activity.window.callback)
            }
            // Window focus arrives before task-transition input is ready on the API 37 phone.
            // Keep the target stable before injecting through the system input dispatcher.
            if (!focused) focusedSince = 0L
            else if (focusedSince == 0L) focusedSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - focusedSince >= 750) return
            Thread.sleep(20)
        }
        fail("Manager did not regain input focus")
    }

    private fun openManager(scenario: ActivityScenario<PasswordManagerActivity>) {
        waitForFocus(scenario)
        touchFlags.clear()
        touchPoints.clear()
        tap(compose.onNodeWithText(open))
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodes(hasText(onDevice) and isEnabled()).fetchSemanticsNodes().size == 1
            }
        } catch (failure: ComposeTimeoutException) {
            var state = "unavailable"
            scenario.onActivity { activity ->
                val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val field = PasswordOwnerActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
                val managerState = listOf("unlocked", "busy").associateWith { name ->
                    val delegate = PasswordManagerActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                    (delegate.get(activity) as androidx.compose.runtime.State<*>).value as Boolean
                }
                state = "foreground=${field.getBoolean(activity)}, focus=${activity.window.decorView.hasWindowFocus()}, " +
                    "secure=${keyguard.isDeviceSecure}, keyguard=${keyguard.isKeyguardLocked}, deviceLocked=${keyguard.isDeviceLocked}, state=$managerState, " +
                    "tapBounds=$tapBounds, touchFlags=${touchFlags.takeLast(8)}, touchPoints=${touchPoints.takeLast(8)}"
            }
            throw AssertionError("Open did not complete: $state", failure)
        }
    }

    private fun tap(node: SemanticsNodeInteraction) {
        val bounds = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
        tapBounds = bounds.toString()
        // Inject through Android's input dispatcher, including the activity's obscured-touch guard.
        // Compose's performClick/semantics actions dispatch directly into its view/callback.
        onView(withId(android.R.id.content)).perform(GeneralClickAction(Tap.SINGLE,
            CoordinatesProvider { view ->
                val location = IntArray(2)
                view.rootView.getLocationOnScreen(location)
                floatArrayOf(bounds.center.x + location[0], bounds.center.y + location[1])
            }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun assertNoSecretInputs() = compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    @Test fun explicitOpenBackgroundAndRecreationRequireFreshPresence() {
        val selected = selectedAccount()
        launch(selected).use { scenario ->
            scenario.onActivity {
                assertTrue("Secure window flag lost", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            compose.onNodeWithText(open).assertExists()
            compose.onNodeWithText(onDevice).assertDoesNotExist()
            openManager(scenario)
            assertNoSecretInputs()
            // Exercise real task switching instead of relying on the harness's helper window.
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (scenario.state != Lifecycle.State.CREATED && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
            assertEquals("Manager did not stop behind Settings", Lifecycle.State.CREATED, scenario.state)
            context.startActivity(Intent(context, PasswordManagerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitForFocus(scenario)
            compose.onNodeWithText(open).assertExists()
            compose.onNodeWithText(onDevice).assertDoesNotExist()
            openManager(scenario)
            scenario.recreate()
            compose.onNodeWithText(open).assertExists()
            compose.onNodeWithText(onDevice).assertDoesNotExist()
            openManager(scenario)
            assertNoSecretInputs()
        }
    }

    @Test fun designatedAccountSyncListsOnlyMetadataWithPasswordHidden() {
        val selected = selectedAccount()
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing existing pending writes", repository.store.locked {
            val state = it.load(); state.mutations.isEmpty() && state.preferenceMutations.isEmpty()
        })
        val wasEnabled = repository.enabled
        try {
            launch(selected).use { scenario ->
                openManager(scenario)
                compose.onNodeWithText(selected + " ✓").assertExists()
                if (!wasEnabled) tap(compose.onNode(isToggleable()))
                else tap(compose.onNodeWithText(context.getString(R.string.passwords_refresh)))
                compose.waitUntil(timeoutMillis = 30_000) {
                    compose.onAllNodesWithText("https://cyc651.example.invalid/").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText("cyc651-synthetic-user").assertExists()
                assertNoSecretInputs()
                scenario.onActivity {
                    assertTrue("Password metadata screen must remain secure", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
            }
        } finally { if (repository.enabled != wasEnabled) repository.setEnabled(wasEnabled) }
    }
}
