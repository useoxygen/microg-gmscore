/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.people.AndroidContactStore
import org.microg.gms.people.ContactSyncPreferences
import org.microg.gms.people.sync.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Production engine and provider, fenced to one new contact and an own synthetic account. */
object LiveRoundTrip {
    private const val ACCOUNT_TYPE = "org.microg.gms.contacts.lab"
    fun checkProof(context: Context, account: Account, nonce: String) {
        check(Build.TYPE in listOf("userdebug", "eng"))
        check(nonce.matches(Regex("[a-f0-9-]{36}")))
        val proof = JSONObject(File(context.filesDir, "roundtrip-device-proof.json").readText())
        check(proof.getString("serial") == "3B241JEKB00555")
        check(proof.getString("nonce") == nonce && proof.getString("fingerprint") == Build.FINGERPRINT)
        check(proof.getString("accountSha256") == AccessProbe.receipt(account).getString("accountSha256"))
        check(System.currentTimeMillis() - proof.getLong("createdAt") in 0..300_000)
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun providerDigest(context: Context): JSONObject = JSONObject().apply {
        for ((name, uri) in listOf("raw" to RawContacts.CONTENT_URI, "data" to Data.CONTENT_URI)) {
            val rows = JSONArray()
            context.contentResolver.query(uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
                null, null, null, "_id ASC")!!.use { cursor ->
                while (cursor.moveToNext()) {
                    check(rows.length() < 100_000)
                    val row = JSONObject()
                    for (i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i), when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP)
                        else -> cursor.getString(i)
                    })
                    rows.put(row)
                }
            }
            put(name, JSONObject().put("rows", rows.length()).put("sha256", digest(canonical(rows))))
        }
    }

    private fun all(api: ContactsApi): List<Person> {
        val people = linkedMapOf<String, Person>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = api.list(null, token)
            page.people.forEach { check(people.put(it.resource, it) == null) }
            check(people.size <= 100_000)
            token = page.nextPage
            if (token != null) check(seen.add(token) && seen.size <= 1000)
            else check(!page.nextSync.isNullOrBlank())
        } while (token != null)
        return people.values.toList()
    }

    private fun googleDigest(people: List<Person>) = digest(people.sortedBy { it.resource }
        .joinToString("\n") { canonical(it.json) })
    private fun named(body: JSONObject, marker: String) = body.optJSONArray("names")?.let {
        it.length() == 1 && it.getJSONObject(0).optString("givenName") == marker
    } == true

    fun run(context: Context, googleAccount: Account, token: String, nonce: String,
        receipt: JSONObject, progress: (String) -> Unit): String {
        val stateFile = File(context.filesDir, "roundtrip-state.json")
        receipt.remove("providerWrites")
        receipt.remove("googleWrites")
        receipt.put("googleWriteRequests", 0).put("isolatedProviderAccount", true)
        val state = JSONObject().put("nonce", nonce).put("accountSha256", receipt.getString("accountSha256"))
        val marker = "Cyclon Contacts test $nonce"
        val fixture = Account("contacts-lab-$nonce", ACCOUNT_TYPE)
        val owned = mutableSetOf<String>()
        val manager = AccountManager.get(context)
        val resolver = context.contentResolver
        var started = false
        var accountCreated = false
        var createDispatched = false
        var beforeProvider: JSONObject? = null
        var beforeGoogle: List<Person>? = null
        var prefs: ContactSyncPreferences? = null
        fun saveState() {
            state.put("resources", JSONArray(owned.toList())).put("createDispatched", createDispatched)
            stateFile.writeText(state.toString(2))
        }
        fun save() {
            File(context.filesDir, "roundtrip-receipt.json").writeText(receipt.toString(2))
            AccessProbe.save(context, receipt)
        }
        fun stage(text: String) {
            receipt.put("status", text)
            save()
            progress(text)
        }
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val raw = PeopleApi(HttpTransport { method, url, body ->
            check(url.startsWith(PeopleApi.ORIGIN))
            if (method != "GET") receipt.put("googleWriteRequests", receipt.getInt("googleWriteRequests") + 1)
            save()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(request).execute().use { HttpReply(it.code, it.body?.string().orEmpty()) }
        }, { true })
        val api = object : ContactsApi {
            override fun list(syncToken: String?, pageToken: String?): Page {
                val page = raw.list(syncToken, pageToken)
                val selected = page.people.filter { person ->
                    person.resource in owned || (named(person.json, marker) &&
                        person.creationId() == state.optString("creationId"))
                }
                selected.filter { !it.deleted && named(it.json, marker) }.forEach { owned.add(it.resource) }
                if (started) saveState()
                return page.copy(people = selected)
            }
            override fun get(resource: String): Person? {
                check(resource in owned)
                return raw.get(resource)?.also { check(it.deleted || named(it.json, marker)) }
            }
            override fun create(body: JSONObject): Person {
                check(started && owned.isEmpty() && !createDispatched && named(body, marker))
                state.put("creationId", body.getJSONArray("clientData").getJSONObject(0).getString("value"))
                createDispatched = true
                saveState() // No POST is ever retried, including a lost reply.
                val person = try { raw.create(body) } catch (e: ApiException) {
                    if (e.definitelyRejected) { createDispatched = false; saveState() }
                    throw e
                }
                check(named(person.json, marker))
                owned.add(person.resource)
                saveState()
                return person
            }
            override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
                check(person.resource in owned && named(person.json, marker))
                check("names" !in fields || named(body, marker))
                checkNotNull(get(person.resource))
                return raw.update(person, fields, body)
            }
            override fun delete(resource: String) {
                check(resource in owned)
                if (get(resource) != null) raw.delete(resource)
            }
        }
        try {
            checkProof(context, googleAccount, nonce)
            check(!stateFile.exists()) { "A previous test requires reconciliation" }
            check(manager.getAccountsByType(ACCOUNT_TYPE).isEmpty())
            beforeProvider = providerDigest(context)
            beforeGoogle = all(raw)
            receipt.put("googleContactsBefore", beforeGoogle.size).put("providerBefore", beforeProvider)
            started = true
            saveState()
            stage("Creating an isolated provider account and one disposable contact")
            accountCreated = manager.addAccountExplicitly(fixture, null, null)
            check(accountCreated)
            prefs = ContactSyncPreferences(context, fixture).apply { configure(SyncMode.TWO_WAY, 0) }
            resolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use { provider ->
                val store = AndroidContactStore(provider, fixture, prefs)
                store.configureMode()
                val operations = arrayListOf(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_NAME, fixture.name).withValue(RawContacts.ACCOUNT_TYPE, fixture.type).build())
                for ((mime, values) in listOf(
                    StructuredName.CONTENT_ITEM_TYPE to ContentValues().apply { put(StructuredName.GIVEN_NAME, marker) },
                    Phone.CONTENT_ITEM_TYPE to ContentValues().apply { put(Phone.NUMBER, "2025550101"); put(Phone.TYPE, Phone.TYPE_MOBILE) },
                    Note.CONTENT_ITEM_TYPE to ContentValues().apply { put(Note.NOTE, "Lab $nonce") })) {
                    operations.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        .withValue(Data.MIMETYPE, mime).withValues(values).build())
                }
                val id = ContentUris.parseId(provider.applyBatch(operations)[0].uri!!)
                fun sync(): SyncReport = ContactsSync(api, store) { prefs.mode }.sync().also {
                    check(it.conflicts == 0 && it.uncertain == 0)
                    receipt.put("lastDownloaded", it.downloaded).put("lastUploaded", it.uploaded)
                    save()
                }
                check(sync().uploaded == 1)
                val resource = owned.single()
                check(api.get(resource)!!.json.getJSONArray("phoneNumbers").getJSONObject(0).getString("value") == "2025550101")
                receipt.put("phoneCreateReachedGoogle", true)
                stage("Verifying a phone edit reaches Google")
                check(resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Phone.NUMBER, "2025550102") },
                    "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE)) == 1)
                check(sync().uploaded == 1)
                check(api.get(resource)!!.json.getJSONArray("phoneNumbers").getJSONObject(0).getString("value") == "2025550102")
                receipt.put("phoneEditReachedGoogle", true)
                stage("Verifying a Google edit reaches the Android provider")
                val note = "Cloud edit $nonce"
                api.update(api.get(resource)!!, setOf("biographies"), JSONObject().put("biographies", JSONArray()
                    .put(JSONObject().put("value", note).put("contentType", "TEXT_PLAIN"))))
                var pulled = false
                repeat(30) {
                    if (!pulled) {
                        sync()
                        pulled = store.contacts().single().fields["biographies"]?.let {
                            JSONArray(it).getJSONObject(0).optString("value") == note
                        } == true
                        if (!pulled) { stage("Waiting for Google change visibility (${it + 1}/30)"); Thread.sleep(1000) }
                    }
                }
                check(pulled)
                receipt.put("googleEditReachedPhone", true)
                stage("Verifying phone deletion and cleaning up the disposable contact")
                check(resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null) == 1)
                check(sync().uploaded == 1)
                check(api.get(resource) == null && store.contacts().isEmpty())
                receipt.put("phoneDeleteReachedGoogle", true).put("roundTripPassed", true)
            }
        } catch (e: Exception) {
            receipt.put("exception", e.javaClass.simpleName)
            if (e is ApiException) receipt.put("httpStatus", e.status)
            receipt.put("status", "Disposable round trip stopped (${e.javaClass.simpleName})")
        } finally {
            if (started) try {
                // An uncertain create retains both journals unless a matching remote can
                // be found. Never claim cleanup based on an eventually consistent empty list.
                if (createDispatched && owned.isEmpty()) {
                    val recovered = all(raw).filter { named(it.json, marker) && it.creationId() == state.optString("creationId") }
                    check(recovered.size == 1)
                    owned.add(recovered.single().resource)
                    saveState()
                }
                for (resource in owned) {
                    api.delete(resource)
                    check(api.get(resource) == null)
                }
                receipt.put("googleCleanupVerified", true)
                if (accountCreated) {
                    runCatching { prefs?.configure(SyncMode.OFF, 0) }
                    val args = arrayOf(fixture.name, fixture.type)
                    val scope = "account_name=? AND account_type=?"
                    resolver.delete(RawContacts.CONTENT_URI.buildUpon()
                        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), scope, args)
                    resolver.delete(ContactsContract.Settings.CONTENT_URI, scope, args)
                    resolver.delete(ContactsContract.SyncState.CONTENT_URI, scope, args)
                    check(manager.removeAccountExplicitly(fixture))
                }
                check(manager.getAccountsByType(ACCOUNT_TYPE).isEmpty())
                receipt.put("providerExistingUnchanged", canonical(providerDigest(context)) == canonical(beforeProvider))
                val after = all(raw).filter { it.resource !in owned }
                receipt.put("googleExistingUnchanged", googleDigest(after) == googleDigest(beforeGoogle!!))
                check(receipt.getBoolean("providerExistingUnchanged") && receipt.getBoolean("googleExistingUnchanged"))
                receipt.put("cleanupRequired", false)
                check(stateFile.delete())
                if (receipt.optBoolean("roundTripPassed")) receipt.put("status", "Two-way disposable contact round trip passed. Google and provider cleanup verified.")
            } catch (e: Exception) {
                receipt.put("cleanupRequired", true).put("cleanupException", e.javaClass.simpleName)
                    .put("status", "Disposable test requires reconciliation; its private journal was retained.")
            }
            save()
        }
        return receipt.getString("status")
    }
}
