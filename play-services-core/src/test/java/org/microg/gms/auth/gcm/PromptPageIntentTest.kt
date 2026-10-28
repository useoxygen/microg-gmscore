/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.gcm

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.accountsettings.ui.EXTRA_ACCOUNT_NAME
import org.microg.gms.accountsettings.ui.PromptPageActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Prompt pages share one task that each start clears, so a resend's page replaces the older one (useoxygen/cyclon#842). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PromptPageIntentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun aPromptPageReplacesTheOlderOneInItsOwnTask() {
        val intent = context.promptPageIntent("owner@example.com", listOf("extra.url" to "https://accounts.google.com/x", null to "dropped"))
        assertEquals(PromptPageActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, intent.flags)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        assertEquals("owner@example.com", intent.getStringExtra(EXTRA_ACCOUNT_NAME))
        assertEquals("https://accounts.google.com/x", intent.getStringExtra("extra.url"))
        assertFalse(intent.hasExtra(EXTRA_PROMPT_OPENED_DIRECT))
    }

    @Test
    fun theFetchedExtrasCannotNameAnotherAccount() {
        val intent = context.promptPageIntent("owner@example.com", listOf(EXTRA_ACCOUNT_NAME to "attacker@example.com"))
        assertEquals("owner@example.com", intent.getStringExtra(EXTRA_ACCOUNT_NAME))
    }
}
