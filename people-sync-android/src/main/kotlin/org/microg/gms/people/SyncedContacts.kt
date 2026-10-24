/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import org.microg.gms.people.sync.PrivatePeopleApi

/**
 * The contacts and labels this sync imported or adopted for one Google account, as Google's own
 * sync removes them when sync is turned off. Pre-existing rows of other origin are never touched.
 */
class SyncedContacts(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences) {
    private val owners = listOf(PrivatePeopleApi.OWNER, AndroidContactStore.OWNER)
    private fun syncUri(uri: Uri) = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private val accountScope = "account_name=? AND account_type=? AND data_set IS NULL"
    private val args get() = arrayOf(account.name, account.type) + owners

    // Owned rows, plus contacts created in this account on this phone after sync was turned on.
    private val rawScope get() = "$accountScope AND (${RawContacts.SYNC4} IN (?,?) OR (${RawContacts.SYNC4} IS NULL AND " +
        "${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts._ID}>${floor(prefs.privateAdoptionFloor, prefs.adoptionFloor)}))"
    private val groupScope get() = "$accountScope AND ${Groups.SYSTEM_ID} IS NULL AND (${Groups.SYNC4} IN (?,?) OR (${Groups.SYNC4} IS NULL AND " +
        "${Groups.SOURCE_ID} IS NULL AND ${Groups._ID}>${floor(prefs.privateGroupAdoptionFloor, prefs.groupAdoptionFloor ?: Long.MAX_VALUE)}))"

    /** Contacts or labels with edits that haven't reached Google yet; removing them loses those edits. */
    fun unsyncedChanges(): Int = count(RawContacts.CONTENT_URI, "$rawScope AND (${RawContacts.DIRTY}!=0 OR ${RawContacts.SYNC3} IS NOT NULL)") +
        count(Groups.CONTENT_URI, "$groupScope AND (${Groups.DIRTY}!=0 OR ${Groups.SYNC3} IS NOT NULL)")

    fun count(): Int = count(RawContacts.CONTENT_URI, "$rawScope AND ${RawContacts.DELETED}=0")

    /** Call only after sync is off. Clears the cursor so turning sync back on downloads everything again. */
    fun remove() {
        check(prefs.mode == org.microg.gms.people.sync.SyncMode.OFF)
        provider.applyBatch(arrayListOf(
            ContentProviderOperation.newDelete(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope, args).build(),
            ContentProviderOperation.newDelete(syncUri(Groups.CONTENT_URI)).withSelection(groupScope, args).build(),
            ContactsContract.SyncState.newSetOperation(account, "{}".toByteArray(Charsets.UTF_8))))
        prefs.resetCheckpoints()
    }

    private fun count(uri: Uri, selection: String) = provider.query(uri, arrayOf("_id"), selection, args, null)?.use { it.count } ?: 0
    private fun floor(private: Long, legacy: Long) = minOf(private, legacy)
}
