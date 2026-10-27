/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CalendarTransferTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account = Account("calendar-fixture@example.invalid", "com.google")
    private lateinit var prefs: CalendarSyncPreferences
    private lateinit var provider: CalendarTransferProvider
    @Before fun setup() {
        context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE).edit().clear().commit()
        AccountManager.get(context).addAccountExplicitly(account, null, null)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        prefs = CalendarSyncPreferences(context, account)
        provider = Robolectric.buildContentProvider(CalendarTransferProvider::class.java).create().get()
    }
    private fun call(method: String = "enableAfterTransfer", name: String = account.name): Bundle = provider.call(method, name, null)

    @Test fun transferRequiresOwnerConsentBeforeFirstEnable() {
        assertFalse(prefs.hasSavedChoice)
        assertEquals("consent_required", call().getString("result"))
        assertFalse(prefs.enabled)
        assertFalse(prefs.twoWay)
        assertFalse(prefs.hasSavedChoice)
    }
    @Test fun transferNeverOverridesSavedOffOrWriteChoice() {
        prefs.configure(true, true)
        prefs.configure(false)
        assertEquals("saved_off", call().getString("result"))
        assertFalse(prefs.enabled)
        assertTrue(prefs.twoWay)
        assertEquals(0, ContentResolver.getIsSyncable(account, CalendarSyncPreferences.AUTHORITY))
    }
    @Test fun existingDownloadConsentNeverBecomesWriteConsent() {
        prefs.configure(true, false)
        assertEquals("enabled", call().getString("result"))
        assertFalse(prefs.twoWay)
        assertFalse(call().getBoolean("two_way"))
    }
    @Test fun migrationHoldSurvivesCompletionReconfigureAndNewPreferencesInstance() {
        prefs.configure(true, true)
        call("beginMigration")
        call()
        prefs.configure(true, true)
        assertTrue(CalendarSyncPreferences(context, account).uploadsHeld)
        assertFalse(prefs.uploadsAllowed)
        prefs.ownerResumeUploads()
        assertTrue(prefs.uploadsAllowed)
    }
    @Test fun completionDoesNotOverrideSystemSyncSettings() {
        prefs.configure(true, true)
        ContentResolver.setSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY, false)
        ContentResolver.setMasterSyncAutomatically(false)
        call()
        assertFalse(ContentResolver.getSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY))
        assertFalse(ContentResolver.getMasterSyncAutomatically())
    }
    @Test fun unknownAccountCannotEnableAnotherAccount() {
        assertEquals("no_account", call(name = "missing@example.invalid").getString("result"))
        assertFalse(prefs.hasSavedChoice)
    }
    @Test fun revokedPermissionsAreReportedWithoutChangingConsent() {
        prefs.configure(true, true)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertEquals("permission_required", call().getString("result"))
        assertTrue(prefs.enabled)
    }
    @Test fun arbitraryBinderCallerCannotStartMigrationOrReadAccountChoices() {
        ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
        try {
            assertThrows(SecurityException::class.java) { call("beginMigration") }
        } finally { ShadowBinder.setCallingUid(android.os.Process.myUid()) }
        assertFalse(prefs.uploadsHeld)
    }
}
