/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/** Opt-in owner UI checks; never opens an editor, reveals a password or mutates a login. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PasswordManagerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val open get() = context.getString(R.string.passwords_open)
    private val onDevice get() = context.getString(R.string.passwords_on_device)

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

    private fun openManager() {
        tap(compose.onNodeWithText(open))
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasText(onDevice) and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }

    private fun tap(node: SemanticsNodeInteraction) {
        val bounds = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
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
            openManager()
            assertNoSecretInputs()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(open).assertExists()
            compose.onNodeWithText(onDevice).assertDoesNotExist()
            openManager()
            scenario.recreate()
            compose.onNodeWithText(open).assertExists()
            compose.onNodeWithText(onDevice).assertDoesNotExist()
            openManager()
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
                openManager()
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
