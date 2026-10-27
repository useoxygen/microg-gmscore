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
class SyncStillRunning : IllegalStateException("A contacts sync of this account is still running")

class SyncedContacts(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences) {
    private val owners = listOf(PrivatePeopleApi.OWNER, AndroidContactStore.OWNER)
    private fun syncUri(uri: Uri) = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private val accountScope = "account_name=? AND account_type=? AND data_set IS NULL"
    private val args get() = arrayOf(account.name, account.type) + owners

    // Unverified imports are not sync-owned, even source-less rows above the
    // adoption floor. Turning sync off must retain those copies and their edits.
    private val rawScope get() = "$accountScope AND ${RawContacts.SYNC4} IN (?,?)" +
        // Adopted but never sent (no Google ID, nothing in flight): Google has no copy, and a sync may adopt a row
        // after the warning was counted. Keep it; turning sync back on uploads it.
        " AND NOT (${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts.SYNC3} IS NULL)"
    // Groups sync created (labels and imported system groups), plus new labels it would adopt (AndroidGroupStore.groups()).
    private val groupScope get() = "$accountScope AND (${Groups.SYNC4} IN (?,?) OR (${Groups.SYNC4} IS NULL AND ${Groups.SYSTEM_ID} IS NULL AND " +
        "${Groups.SOURCE_ID} IS NULL AND ${Groups._ID}>${prefs.privateGroupAdoptionFloor} AND ${Groups.DIRTY}=1))"

    /** Contacts or labels with edits that haven't reached Google yet; removing them loses those edits. */
    fun unsyncedChanges(): Int = count(RawContacts.CONTENT_URI, "$rawScope AND (${RawContacts.DIRTY}!=0 OR ${RawContacts.SYNC3} IS NOT NULL)") +
        count(Groups.CONTENT_URI, "$groupScope AND (${Groups.DIRTY}!=0 OR ${Groups.SYNC3} IS NOT NULL)")

    fun count(): Int = count(RawContacts.CONTENT_URI, "$rawScope AND ${RawContacts.DELETED}=0")

    /**
     * Call only after sync is off. Waits for a running sync of this account to finish, so none of its
     * writes land after the removal. Clears the cursor so turning sync back on downloads everything again.
     */
    fun remove(waitMillis: Long = 120_000) {
        check(prefs.mode == org.microg.gms.people.sync.SyncMode.OFF)
        val lock = ContactSyncLock.of(account)
        // Bounded and interruptible: a stalled sync must not block turn-off forever. The caller keeps
        // prefs.pendingRemoval set and retries later; sync itself is already off.
        if (!lock.tryLock(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) throw SyncStillRunning()
        try { removeLocked(); prefs.pendingRemoval = false } finally { lock.unlock() }
    }
    private fun removeLocked() {
        check(prefs.mode == org.microg.gms.people.sync.SyncMode.OFF)
        if (!prefs.pendingRemoval) return // Cancelled by turning sync back on.
        provider.applyBatch(arrayListOf(
            ContentProviderOperation.newDelete(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope, args).build(),
            ContentProviderOperation.newDelete(syncUri(Groups.CONTENT_URI)).withSelection(groupScope, args).build(),
            ContactsContract.SyncState.newSetOperation(account, "{}".toByteArray(Charsets.UTF_8))))
        prefs.resetCheckpoints()
    }

    private fun count(uri: Uri, selection: String) = provider.query(uri, arrayOf("_id"), selection, args, null)?.use { it.count } ?: 0
}
