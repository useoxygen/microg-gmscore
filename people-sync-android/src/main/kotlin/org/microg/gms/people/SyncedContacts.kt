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

    // Exactly what the sync engine treats as its own: rows it owns, plus new rows it would adopt
    // (created in this account after enabling, never uploaded, still dirty). See AndroidContactStore.contacts().
    private val adoptionFloor get() = if (prefs.uploadNewContacts) prefs.privateAdoptionFloor else Long.MAX_VALUE
    private val rawScope get() = "$accountScope AND (${RawContacts.SYNC4} IN (?,?) OR (${RawContacts.SYNC4} IS NULL AND " +
        "${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts._ID}>$adoptionFloor AND ${RawContacts.DIRTY}=1))"
    // New contacts made on this phone (or moved here from device storage) that never reached Google, with nothing
    // in flight: turning sync off keeps them as device contacts instead of deleting them.
    private val keptScope get() = "$accountScope AND ${RawContacts.SYNC4} IS NULL AND ${RawContacts.SOURCE_ID} IS NULL AND " +
        "${RawContacts.SYNC3} IS NULL AND ${RawContacts._ID}>$adoptionFloor AND ${RawContacts.DIRTY}=1 AND ${RawContacts.DELETED}=0"
    private val accountArgs get() = arrayOf(account.name, account.type)
    // Groups sync created (labels and imported system groups), plus new labels it would adopt (AndroidGroupStore.groups()).
    private val groupScope get() = "$accountScope AND (${Groups.SYNC4} IN (?,?) OR (${Groups.SYNC4} IS NULL AND ${Groups.SYSTEM_ID} IS NULL AND " +
        "${Groups.SOURCE_ID} IS NULL AND ${Groups._ID}>${prefs.privateGroupAdoptionFloor} AND ${Groups.DIRTY}=1))"

    /** Contacts or labels with edits that haven't reached Google yet and would be lost; kept new contacts excluded. */
    fun unsyncedChanges(): Int = count(RawContacts.CONTENT_URI, "$rawScope AND (${RawContacts.DIRTY}!=0 OR ${RawContacts.SYNC3} IS NOT NULL)") +
        count(Groups.CONTENT_URI, "$groupScope AND (${Groups.DIRTY}!=0 OR ${Groups.SYNC3} IS NOT NULL)") - keptOnPhone()

    /** New contacts that never reached Google; turning sync off keeps them on this phone. */
    fun keptOnPhone(): Int = provider.query(RawContacts.CONTENT_URI, arrayOf("_id"), keptScope, accountArgs, null)?.use { it.count } ?: 0

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
        // First keep never-uploaded contacts as device contacts, each in its own transaction, so nothing created
        // or moved here is lost. If this stops halfway, the removal is retried and the rest are kept then.
        val kept = provider.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), keptScope, accountArgs, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        val device = DeviceContacts(provider)
        for (id in kept) device.keepOnPhone(id, keptScope, accountArgs)
        provider.applyBatch(arrayListOf(
            // A contact saved here since the list above would be deleted: fail the whole removal instead. The caller
            // keeps pendingRemoval, and the retry keeps that contact first.
            ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI).withSelection(keptScope, accountArgs).withExpectedCount(0).build(),
            ContentProviderOperation.newDelete(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope, args).build(),
            ContentProviderOperation.newDelete(syncUri(Groups.CONTENT_URI)).withSelection(groupScope, args).build(),
            ContactsContract.SyncState.newSetOperation(account, "{}".toByteArray(Charsets.UTF_8))))
        prefs.resetCheckpoints()
    }

    private fun count(uri: Uri, selection: String) = provider.query(uri, arrayOf("_id"), selection, args, null)?.use { it.count } ?: 0
}
