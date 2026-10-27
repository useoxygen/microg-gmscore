/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.people.sync.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ContactsLegacyCleanupTest {
    private val account = Account("legacy-fixture@invalid", "com.google")
    private data class Row(val id: Long, val owner: String?, val account: Account,
        var dirty: Boolean = false, val pending: String? = null, val dataset: String? = null)
    /** Exercises real ContentProviderOperation assertions and deletes on synthetic rows. */
    private inner class Provider : ContentProvider() {
        val contacts = mutableListOf<Row>(); val groups = mutableListOf<Row>()
        var state: ByteArray? = null; var foreignMember = false; var queries = 0; var deletions = 0
        var beforeBatch: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        private fun table(uri: Uri) = if (uri.pathSegments.first() == "raw_contacts") contacts else groups
        private fun selected(uri: Uri, selection: String?, args: Array<out String>?): List<Row> = table(uri).filter { row ->
            row.account.name == args!![0] && row.account.type == args[1] && row.dataset == null && row.owner == args[2] &&
                (selection?.contains("dirty!=0") != true || row.dirty || row.pending != null)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++
            val columns = projection ?: arrayOf("_id")
            return MatrixCursor(columns).apply {
                when (uri.pathSegments.first()) {
                    "syncstate" -> state?.let { bytes -> addRow(columns.map { if (it == "data") bytes else 1L }) }
                    "data" -> if (foreignMember) addRow(columns.map { 1L })
                    else -> for (row in selected(uri, selection, args)) addRow(columns.map {
                        when (it) { "_id" -> row.id; "dirty" -> if (row.dirty) 1L else 0L; "sync3" -> row.pending; else -> error("Unexpected column") }
                    })
                }
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            assertEquals("syncstate", uri.pathSegments.first()); state = values!!.getAsByteArray("data")
            return ContentUris.withAppendedId(uri, 1)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Unexpected update")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            val selected = selected(uri, selection, args); deletions += selected.size
            table(uri).removeAll(selected.toSet()); return selected.size
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            beforeBatch?.invoke()
            val oldContacts = contacts.map { it.copy() }; val oldGroups = groups.map { it.copy() }; val oldState = state; val oldDeletes = deletions
            try { return super.applyBatch(operations) }
            catch (error: OperationApplicationException) {
                contacts.clear(); contacts.addAll(oldContacts); groups.clear(); groups.addAll(oldGroups)
                state = oldState; deletions = oldDeletes; throw error
            }
        }
    }
    private fun <T> withProvider(block: (Provider, android.content.ContentProviderClient, ContactSyncPreferences) -> T): T {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        provider.attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(SyncMode.DOWNLOAD, 0)
        return context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use { block(provider, it, prefs) }
    }
    private fun store(client: ContentProviderClient, prefs: ContactSyncPreferences) = AndroidContactStore(client, account, prefs,
        backendOwner = PrivatePeopleApi.OWNER, checkpointBinding = ContactSyncService.privatePeopleContext.binding)

    @Test fun cleanLegacyImportsAreRemovedOnceBeforeFullPrivateDownload() = withProvider { provider, client, prefs ->
        provider.contacts.addAll(listOf(Row(1, AndroidContactStore.OWNER, account), Row(2, PrivatePeopleApi.OWNER, account),
            Row(3, null, account), Row(4, AndroidContactStore.OWNER, Account("other@invalid", account.type)),
            Row(5, AndroidContactStore.OWNER, account, dataset = "other-dataset")))
        provider.groups.addAll(listOf(Row(6, AndroidContactStore.OWNER, account), Row(7, PrivatePeopleApi.OWNER, account)))
        val existing = store(client, prefs)
        existing.checkpoint("old-private-cursor")
        prefs.privateGroupCheckpoint(ContactSyncService.privatePeopleContext.binding, "old-group-cursor")
        assertTrue(existing.preparePrivateSync())
        assertEquals(listOf(2L, 3L, 4L, 5L), provider.contacts.map { it.id }); assertEquals(listOf(7L), provider.groups.map { it.id })
        assertEquals(2, provider.deletions); assertNull(existing.checkpoint())
        assertNull(prefs.privateGroupCheckpoint(ContactSyncService.privatePeopleContext.binding))
        assertTrue(ContactSyncPreferences(RuntimeEnvironment.getApplication(), account).privateLegacyCleanupComplete)
        val queries = provider.queries
        assertTrue(store(client, prefs).preparePrivateSync()); assertEquals(queries, provider.queries)
    }
    @Test fun dirtyOrPendingLegacyRowsStopTheServiceBeforeAuthOrImport() = withProvider { provider, client, prefs ->
        val service = Robolectric.buildService(ContactSyncService::class.java).create().get()
        val getter = ContactSyncService::class.java.getDeclaredMethod("getAdapter").apply { isAccessible = true }
        val adapter = getter.invoke(service) as AbstractThreadedSyncAdapter
        for (group in listOf(false, true)) for (pending in listOf(false, true)) {
            provider.contacts.clear(); provider.groups.clear()
            val row = Row(1, AndroidContactStore.OWNER, account, dirty = !pending, pending = if (pending) "{}" else null)
            if (group) provider.groups.add(row) else provider.contacts.add(row)
            adapter.onPerformSync(account, Bundle(), ContactsContract.AUTHORITY, client, SyncResult())
            assertEquals("needs_review", prefs.status); assertEquals(0, provider.deletions)
            assertFalse(prefs.privateLegacyCleanupComplete); assertNull(prefs.blockedClientContext)
            assertEquals(1, provider.contacts.size + provider.groups.size)
        }
    }
    @Test fun concurrentLegacyEditFailsTheAtomicCleanupAndPreservesRows() = withProvider { provider, client, prefs ->
        provider.contacts.add(Row(1, AndroidContactStore.OWNER, account))
        provider.beforeBatch = { provider.contacts.single().dirty = true }
        assertFalse(store(client, prefs).preparePrivateSync())
        assertTrue(provider.contacts.single().dirty); assertEquals(0, provider.deletions)
        assertFalse(prefs.privateLegacyCleanupComplete)
    }
    @Test fun legacyGroupWithNonLegacyMembersCannotCascadeIntoTheirData() = withProvider { provider, client, prefs ->
        provider.contacts.add(Row(1, AndroidContactStore.OWNER, account))
        provider.groups.add(Row(2, AndroidContactStore.OWNER, account)); provider.foreignMember = true
        assertFalse(store(client, prefs).preparePrivateSync())
        assertEquals(1, provider.contacts.size); assertEquals(1, provider.groups.size)
        assertEquals(0, provider.deletions); assertFalse(prefs.privateLegacyCleanupComplete)
    }
}
