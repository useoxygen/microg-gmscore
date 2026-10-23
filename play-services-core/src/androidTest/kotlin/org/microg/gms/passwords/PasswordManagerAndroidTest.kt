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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
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
import java.security.MessageDigest
import java.util.UUID

/** Opt-in owner UI checks. Editor mutations need a separate synthetic-only authorization. */
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
    private var metadataStep = "precondition"
    private var metadataFailedAt: String? = null
    private var metadataRestored = false

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

    private fun tap(node: SemanticsNodeInteraction, dialog: Boolean = false) {
        val bounds = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
        tapBounds = bounds.toString()
        // Inject through Android's input dispatcher, including the activity's obscured-touch guard.
        // Compose's performClick/semantics actions dispatch directly into its view/callback.
        val target = onView(withId(android.R.id.content))
        (if (dialog) target.inRoot(isDialog()) else target).perform(GeneralClickAction(Tap.SINGLE,
            CoordinatesProvider { view ->
                val location = IntArray(2)
                view.rootView.getLocationOnScreen(location)
                floatArrayOf(bounds.center.x + location[0], bounds.center.y + location[1])
            }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun assertNoSecretInputs() {
        // The manager's search field is editable metadata; the password/note editor must be absent.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.passwords_search)).assertExists()
        compose.onNodeWithText(context.getString(R.string.passwords_password)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.passwords_note)).assertDoesNotExist()
    }

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
                val search = compose.onNode(hasSetTextAction()).performScrollTo()
                search.performTextReplacement("cyc654-no-match-" + UUID.randomUUID())
                compose.onNodeWithText("https://cyc651.example.invalid/").assertDoesNotExist()
                search.performTextClearance()
                compose.onNodeWithText("https://cyc651.example.invalid/").assertExists()
                scenario.onActivity {
                    assertTrue("Password metadata screen must remain secure", it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
            }
        } finally { if (repository.enabled != wasEnabled) repository.setEnabled(wasEnabled) }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun ownerEditsSyntheticMetadataWithSecureDialogAndExactRestoration() {
        val selected = selectedAccount()
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        // Semantics failures can include editable contents. Never attach them to a test result.
        try { editMetadata(selected) } catch (failure: Throwable) {
            val step = if (metadataRestored) metadataFailedAt ?: metadataStep else metadataStep
            throw AssertionError("Synthetic metadata UI verification failed: step=$step, " +
                "restored=$metadataRestored, failureType=${failure.javaClass.simpleName}; no editable contents are reported")
        }
    }

    private fun editMetadata(selected: String) {
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        val checkpoint = AndroidVaultStore(context, "password-fixture-metadata-restore/" + account.name)
        val wasEnabled = repository.enabled
        assertTrue("Refusing pending writes", repository.store.locked {
            val state = it.load(); state.mutations.isEmpty() && state.preferenceMutations.isEmpty()
        })
        try {
            metadataStep = "initial-sync"
            repository.setEnabled(true)
            repository.refresh()
            val entries = repository.list(false)
            val original = entries.single { it.data.realm == "https://cyc651.example.invalid/" &&
                it.data.username == "cyc651-synthetic-user" && !it.data.blocked }
            // Only this fixture's edit action may be selected by this UI test.
            assertTrue("Editor selection requires the designated fixture alone", entries.count { !it.data.blocked } == 1)
            val untouched = entries.filter { it.id != original.id }.associate { it.id to it.data.encode() }
            val restore = checkpoint.locked { session ->
                session.load().chromeLocalData["original"] ?: original.data.encode().also {
                    session.save(VaultState(chromeLocalData = hashMapOf("original" to it)))
                }
            }
            val data = PasswordData.decode(restore)
            assertTrue("Restore input is outside the fixture", data.tag() == original.id &&
                data.realm == "https://cyc651.example.invalid/" && data.username == "cyc651-synthetic-user")
            try {
                metadataStep = "open-manager"
                launch(selected).use { scenario ->
                    openManager(scenario)
                    compose.waitUntil(30_000) { compose.onAllNodesWithText("cyc651-synthetic-user").fetchSemanticsNodes().size == 1 }
                    metadataStep = "open-editor"
                    tap(compose.onNodeWithText(context.getString(R.string.passwords_edit)).performScrollTo())
                    compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.passwords_note)).fetchSemanticsNodes().isNotEmpty() }
                    metadataStep = "secure-dialog"
                    scenario.onActivity {
                        fun descendants(view: View): Sequence<View> = sequence {
                            yield(view)
                            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
                        }
                        val windows = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                            .filterIsInstance<DialogWindowProvider>().map { it.window }.distinct().toList()
                        assertTrue("Editor dialog window unavailable", windows.size == 1)
                        assertTrue("Editor dialog lost FLAG_SECURE", windows.single().attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                        assertTrue("Editor dialog lacks its obscured-touch guard", windows.single().callback is PasswordDialogTouchGuard)
                    }
                    val name = "cyc654-name-" + UUID.randomUUID()
                    val note = "cyc654-note-" + UUID.randomUUID()
                    metadataStep = "edit-name"
                    compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.passwords_name)))
                        .performScrollTo().performTextReplacement(name)
                    metadataStep = "edit-note"
                    compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.passwords_note)))
                        .performScrollTo().performTextReplacement(note)
                    metadataStep = "close-keyboard"
                    closeSoftKeyboard()
                    // Save uses Android input delivery through the dialog's own Window callback.
                    metadataStep = "tap-save"
                    tap(compose.onNodeWithText(context.getString(R.string.passwords_save)), dialog = true)
                    metadataStep = "readback"
                    compose.waitUntil(30_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().size == 1 }
                    val readback = PasswordRepository(context, account).list(false).single { it.id == original.id }.data
                    assertTrue("Metadata edit was not persisted", readback.displayName == name && readback.defaultNote?.value == note)
                    fun withoutMetadata(value: PasswordData) = Proto.decode(value.encode()).replace(15, null).replace(22, null).encode()
                    assertTrue("Metadata edit changed other password fields", MessageDigest.isEqual(withoutMetadata(data), withoutMetadata(readback)))
                }
            } catch (failure: Throwable) { metadataFailedAt = metadataStep; throw failure }
            finally {
                // Ordinary partial-client upsert intentionally retains omitted fields. Restore the
                // exact original through the production journal with the latest server version.
                metadataStep = "restore-reconcile"
                repository.refresh()
                repository.store.locked { session ->
                    val state = session.load()
                    assertTrue("Uncertain edit retained; checkpoint preserved", state.mutations.isEmpty())
                    val current = state.records.single { Proto.decode(it).string(2) == original.id }
                    val keys = VaultKeys.unlock(state.metadata!!, customKey = state.customKey)
                    try {
                        val desired = PasswordResourceCodec.encrypt(data, keys, current)
                        VaultSync(PasswordApi(PasswordTransport { _, _ -> error("Only local enqueue is allowed") }), repository.store)
                            .enqueue(session, MutationKind.UPDATE, original.id, desired)
                    } finally { keys.destroy() }
                }
                metadataStep = "restore-upload"
                val receipt = repository.refresh()
                repository.refresh()
                val after = repository.list(false)
                metadataStep = "restore-verify"
                assertTrue("Exact restoration is unverified", receipt.conflicts == 0 && receipt.indeterminate == 0 &&
                    MessageDigest.isEqual(restore, after.single { it.id == original.id }.data.encode()))
                val others = after.filter { it.id != original.id }.associate { it.id to it.data.encode() }
                assertTrue("Unrelated record changed", untouched.keys == others.keys &&
                    untouched.all { (id, bytes) -> MessageDigest.isEqual(bytes, others.getValue(id)) })
                checkpoint.clear()
                metadataRestored = true
            }
        } finally { if (repository.enabled != wasEnabled) repository.setEnabled(wasEnabled) }
    }
}
