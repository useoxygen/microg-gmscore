/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.calendar.CalendarSyncPreferences
import org.microg.gms.people.ContactSyncPreferences
import org.microg.gms.people.sync.SyncMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SyncPasswordsAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val account = Account("owner@example.com", "com.google")

    // On: Android's password-service page, which Settings resolves only with this app's package: URI.
    @Test fun autofillOnOpensTheProviderPageForThisApp() {
        val page = Intent("android.settings.CREDENTIAL_PROVIDER", Uri.parse("package:${context.packageName}"))
        shadowOf(context.packageManager).addResolveInfoForIntent(page, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "CredentialProvider" }
        })
        val intent = SyncPasswords.autofillIntent(context, on = true)!!
        assertEquals("android.settings.CREDENTIAL_PROVIDER", intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    // Contacts sync and its sync adapter write in other processes than these screens (:ui): a preferences file
    // changed on disk behind this process's cache must show on the next read.
    @Test fun readerSeesPreferencesWrittenByAnotherProcess() {
        val prefs = ContactSyncPreferences(context, account)
        prefs.ensureDefaults()
        assertEquals(SyncMode.TWO_WAY, prefs.mode)
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${ContactSyncPreferences.PREFERENCES}.xml")
        assertTrue(file.exists())
        Thread.sleep(1100) // a different modification time, as another process's write has
        file.writeText(file.readText().replace(">TWO_WAY<", ">OFF<"))
        runBlocking { ServiceHealthReader(context).read() }
        assertEquals(SyncMode.OFF, prefs.mode)
    }

    @Test fun autofillOffAsksAndroidToUseThisApp() {
        val intent = SyncPasswords.autofillIntent(context, on = false)!!
        assertEquals(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    // Android's own Account sync switch off: the screens offer "Turn sync back on", which schedules it again.
    @Test fun contactsTurnSyncBackOnUndoesAndroidsSwitch() {
        val prefs = ContactSyncPreferences(context, account)
        prefs.ensureDefaults()
        ContentResolver.setSyncAutomatically(account, ContactSyncPreferences.AUTHORITY, false)
        assertTrue(prefs.androidSyncOff)
        prefs.resumeAndroidSync()
        assertFalse(prefs.androidSyncOff)
        assertTrue(ContentResolver.getSyncAutomatically(account, ContactSyncPreferences.AUTHORITY))
    }

    @Test fun calendarTurnSyncBackOnUndoesAndroidsSwitch() {
        val prefs = CalendarSyncPreferences(context, account)
        prefs.configure(true)
        ContentResolver.setIsSyncable(account, CalendarSyncPreferences.AUTHORITY, 0)
        assertTrue(prefs.androidSyncOff)
        prefs.resumeAndroidSync()
        assertFalse(prefs.androidSyncOff)
        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarSyncPreferences.AUTHORITY))
    }

    // Off stays off: the button never turns on a sync the owner switched off here.
    @Test fun turnSyncBackOnLeavesAnOwnerChoiceOfOffAlone() {
        val prefs = CalendarSyncPreferences(context, account)
        prefs.configure(false)
        assertFalse(prefs.androidSyncOff)
        prefs.resumeAndroidSync()
        assertFalse(ContentResolver.getSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY))
    }
}
