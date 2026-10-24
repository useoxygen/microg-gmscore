/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.people.sync.UploadDisabled
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ImportedContactConsentTest {
    @Test fun defaultIsLocalAndOwnerChoiceSurvivesFreshPreferences() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("contacts-sync", 0).edit().clear().commit()
        val account = Account("synthetic@example.invalid", "com.google")
        val prefs = ContactSyncPreferences(context, account)
        assertFalse(prefs.uploadNewContacts)
        prefs.uploadNewContacts = true
        assertTrue(ContactSyncPreferences(context, account).uploadNewContacts)
        prefs.uploadNewContacts = false
        assertFalse(ContactSyncPreferences(context, account).uploadNewContacts)
    }
    @Test fun disabledImportUploadStopsAtTheActualHttpBoundary() {
        val api = ContactSyncService.contactsApi("synthetic-never-sent", canCreate = { false }) { true }
        assertThrows(UploadDisabled::class.java) { api.create(JSONObject()) }
    }
}
