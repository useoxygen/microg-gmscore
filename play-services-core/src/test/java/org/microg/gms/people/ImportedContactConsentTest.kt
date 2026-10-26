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
    @Test fun defaultMatchesGoogleAndIgnoresOldPrototypeGates() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("contacts-sync", 0).edit().clear().commit()
        val account = Account("synthetic@example.invalid", "com.google")
        val prefs = ContactSyncPreferences(context, account)
        assertTrue(prefs.uploadNewContacts)
        prefs.uploadNewContacts = true
        assertTrue(ContactSyncPreferences(context, account).uploadNewContacts)
        prefs.uploadNewContacts = false
        assertTrue(ContactSyncPreferences(context, account).uploadNewContacts)
        assertEquals(org.microg.gms.people.sync.SyncMode.TWO_WAY, prefs.mode)
        context.getSharedPreferences("contacts-migration-privacy", 0).edit().putBoolean("uploadHold", true).commit()
        assertFalse(ContactMigrationPrivacy(context).held)
        assertEquals(0L, prefs.adoptionFloor)
        prefs.configure(org.microg.gms.people.sync.SyncMode.OFF, 0)
        prefs.ensureDefaults()
        assertEquals(org.microg.gms.people.sync.SyncMode.OFF, ContactSyncPreferences(context, account).mode)
    }
    @Test fun transferHoldSurvivesRestartAndCannotBeReleasedByTheProvider() {
        val context = RuntimeEnvironment.getApplication()
        val privacy = ContactMigrationPrivacy(context)
        privacy.developerControlsEnabled = true
        privacy.beginMigration()
        assertTrue(ContactMigrationPrivacy(context).held)
        val provider = org.robolectric.Robolectric.buildContentProvider(ContactMigrationPrivacyProvider::class.java).create().get()
        assertNull(provider.call("resume", null, null))
        assertTrue(ContactMigrationPrivacy(context).held)
        val api = ContactSyncService.privateContactsApi("synthetic-never-sent") { !ContactMigrationPrivacy(context).held }
        assertThrows(UploadDisabled::class.java) { api.create(newContact(api)) }
        assertThrows(UploadDisabled::class.java) { api.delete("people/fsa_17") }
        assertThrows(UploadDisabled::class.java) {
            api.update(org.microg.gms.people.sync.Person("people/fsa_17", JSONObject()), setOf("names"), JSONObject())
        }
        privacy.ownerResume(); assertFalse(ContactMigrationPrivacy(context).held)
        val prefs = ContactSyncPreferences(context, Account("synthetic@example.invalid", "com.google"))
        prefs.uploadNewContacts = false; assertFalse(prefs.uploadNewContacts)
        privacy.developerControlsEnabled = false; assertTrue(prefs.uploadNewContacts)
    }
    @Test fun providerCallRejectsUnprivilegedRemoteUid() {
        val provider = org.robolectric.Robolectric.buildContentProvider(ContactMigrationPrivacyProvider::class.java).create().get()
        org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
        try { assertThrows(SecurityException::class.java) { provider.call("beginMigration", null, null) } }
        finally { org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid()) }
    }
    @Test fun disabledImportUploadStopsAtTheActualHttpBoundary() {
        val api = ContactSyncService.privateContactsApi("synthetic-never-sent", canCreate = { false }) { true }
        assertThrows(UploadDisabled::class.java) { api.create(newContact(api)) }
    }
    private fun newContact(api: org.microg.gms.people.sync.PrivatePeopleApi) = JSONObject()
        .put("names", org.json.JSONArray().put(JSONObject().put("givenName", "Synthetic")))
        .put("clientData", org.json.JSONArray().put(JSONObject().put("key", org.microg.gms.people.sync.Person.CREATE_KEY).put("value", api.newCreationId())))
}
