/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.ContactsContract.Data
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.people.sync.ContactDates
import org.microg.gms.people.sync.Fields
import org.microg.gms.people.sync.PeopleApi
import org.microg.gms.people.sync.Person
import org.microg.gms.people.sync.canonical

/** Only mapped fields enter an update mask. Other cloud fields remain untouched. */
object ContactFieldMapper {
    const val USER_DEFINED_MIME = "vnd.com.google.cursor.item/contact_user_defined_field"
    private val imProtocols = mapOf("aim" to Im.PROTOCOL_AIM, "msn" to Im.PROTOCOL_MSN,
        "yahoo" to Im.PROTOCOL_YAHOO, "skype" to Im.PROTOCOL_SKYPE, "qq" to Im.PROTOCOL_QQ,
        "googleTalk" to Im.PROTOCOL_GOOGLE_TALK, "icq" to Im.PROTOCOL_ICQ,
        "jabber" to Im.PROTOCOL_JABBER, "netMeeting" to Im.PROTOCOL_NETMEETING)
    private val eventTypes = mapOf("anniversary" to Event.TYPE_ANNIVERSARY, "other" to Event.TYPE_OTHER)
    data class Spec(val field: String, val mime: String, val columns: Map<String, String>,
        val typeColumn: String? = null, val labelColumn: String? = null,
        val types: Map<String, Int> = emptyMap())
    val specs = listOf(
        Spec("names", StructuredName.CONTENT_ITEM_TYPE, linkedMapOf(
            "givenName" to StructuredName.GIVEN_NAME, "middleName" to StructuredName.MIDDLE_NAME,
            "familyName" to StructuredName.FAMILY_NAME, "honorificPrefix" to StructuredName.PREFIX,
            "honorificSuffix" to StructuredName.SUFFIX, "phoneticGivenName" to StructuredName.PHONETIC_GIVEN_NAME,
            "phoneticMiddleName" to StructuredName.PHONETIC_MIDDLE_NAME,
            "phoneticFamilyName" to StructuredName.PHONETIC_FAMILY_NAME)),
        Spec("phoneNumbers", Phone.CONTENT_ITEM_TYPE, mapOf("value" to Phone.NUMBER), Phone.TYPE, Phone.LABEL,
            mapOf("home" to Phone.TYPE_HOME, "work" to Phone.TYPE_WORK, "mobile" to Phone.TYPE_MOBILE,
                "homeFax" to Phone.TYPE_FAX_HOME, "workFax" to Phone.TYPE_FAX_WORK, "pager" to Phone.TYPE_PAGER,
                "main" to Phone.TYPE_MAIN, "other" to Phone.TYPE_OTHER)),
        Spec("emailAddresses", Email.CONTENT_ITEM_TYPE, mapOf("value" to Email.ADDRESS, "displayName" to Email.DISPLAY_NAME), Email.TYPE, Email.LABEL,
            mapOf("home" to Email.TYPE_HOME, "work" to Email.TYPE_WORK, "mobile" to Email.TYPE_MOBILE, "other" to Email.TYPE_OTHER)),
        Spec("addresses", StructuredPostal.CONTENT_ITEM_TYPE, linkedMapOf(
            "formattedValue" to StructuredPostal.FORMATTED_ADDRESS, "streetAddress" to StructuredPostal.STREET,
            "poBox" to StructuredPostal.POBOX, "extendedAddress" to StructuredPostal.NEIGHBORHOOD,
            "city" to StructuredPostal.CITY, "region" to StructuredPostal.REGION,
            "postalCode" to StructuredPostal.POSTCODE, "country" to StructuredPostal.COUNTRY),
            StructuredPostal.TYPE, StructuredPostal.LABEL,
            mapOf("home" to StructuredPostal.TYPE_HOME, "work" to StructuredPostal.TYPE_WORK, "other" to StructuredPostal.TYPE_OTHER)),
        Spec("organizations", Organization.CONTENT_ITEM_TYPE, linkedMapOf(
            "name" to Organization.COMPANY, "title" to Organization.TITLE, "department" to Organization.DEPARTMENT,
            "jobDescription" to Organization.JOB_DESCRIPTION, "symbol" to Organization.SYMBOL,
            "phoneticName" to Organization.PHONETIC_NAME), Organization.TYPE, Organization.LABEL,
            mapOf("work" to Organization.TYPE_WORK, "other" to Organization.TYPE_OTHER)),
        Spec("biographies", Note.CONTENT_ITEM_TYPE, mapOf("value" to Note.NOTE)),
        Spec("nicknames", Nickname.CONTENT_ITEM_TYPE, mapOf("value" to Nickname.NAME)),
        Spec("relations", Relation.CONTENT_ITEM_TYPE, mapOf("person" to Relation.NAME), Relation.TYPE, Relation.LABEL,
            mapOf("assistant" to Relation.TYPE_ASSISTANT, "brother" to Relation.TYPE_BROTHER,
                "child" to Relation.TYPE_CHILD, "domesticPartner" to Relation.TYPE_DOMESTIC_PARTNER,
                "father" to Relation.TYPE_FATHER, "friend" to Relation.TYPE_FRIEND,
                "manager" to Relation.TYPE_MANAGER, "mother" to Relation.TYPE_MOTHER,
                "parent" to Relation.TYPE_PARENT, "partner" to Relation.TYPE_PARTNER,
                "referredBy" to Relation.TYPE_REFERRED_BY, "relative" to Relation.TYPE_RELATIVE,
                "sister" to Relation.TYPE_SISTER, "spouse" to Relation.TYPE_SPOUSE)),
        Spec("sipAddresses", SipAddress.CONTENT_ITEM_TYPE, mapOf("value" to SipAddress.SIP_ADDRESS), SipAddress.TYPE, SipAddress.LABEL,
            mapOf("home" to SipAddress.TYPE_HOME, "work" to SipAddress.TYPE_WORK, "other" to SipAddress.TYPE_OTHER)),
        Spec("imClients", Im.CONTENT_ITEM_TYPE, mapOf("username" to Im.DATA), Im.TYPE, Im.LABEL,
            mapOf("home" to Im.TYPE_HOME, "work" to Im.TYPE_WORK, "other" to Im.TYPE_OTHER)),
        Spec("userDefined", USER_DEFINED_MIME, mapOf("key" to Data.DATA1, "value" to Data.DATA2)),
        Spec("urls", Website.CONTENT_ITEM_TYPE, mapOf("value" to Website.URL), Website.TYPE, Website.LABEL,
            mapOf("home" to Website.TYPE_HOME, "work" to Website.TYPE_WORK, "blog" to Website.TYPE_BLOG,
                "profile" to Website.TYPE_PROFILE, "other" to Website.TYPE_OTHER))
    )
    val managedMimes = specs.map { it.mime }.toSet() + Event.CONTENT_ITEM_TYPE
    private val primaryFields = setOf("phoneNumbers", "emailAddresses", "addresses", "organizations", "nicknames", "urls")
    fun supported(row: ContentValues): Boolean {
        val mime = row.getAsString(Data.MIMETYPE)
        if (mime == Event.CONTENT_ITEM_TYPE) return ContactDates.parse(row.getAsString(Event.START_DATE).orEmpty()) != null
        return mime in specs.map { it.mime }
    }

    /** Import ownership/deletion requires every populated native value to survive projection. */
    fun losslessImport(row: ContentValues, normalize: (String?, ContentValues) -> ContentValues? = { _, _ -> null }): Boolean {
        val mime = row.getAsString(Data.MIMETYPE)
        val reconstructed = when (mime) {
            Photo.CONTENT_ITEM_TYPE -> ContentValues().apply {
                put(Photo.PHOTO, row.getAsByteArray(Photo.PHOTO)); put(Data.IS_PRIMARY, 1)
                // DATA14 is the provider's photo-file reference. Its content is
                // already verified separately by photoToken/photoBytes.
                row.getAsString(Photo.PHOTO_FILE_ID)?.let {
                    val id = it.toLongOrNull()?.takeIf { it > 0 } ?: return false
                    put(Photo.PHOTO_FILE_ID, id)
                }
            }
            GroupMembership.CONTENT_ITEM_TYPE -> ContentValues().apply {
                put(GroupMembership.GROUP_ROW_ID, row.getAsString(GroupMembership.GROUP_ROW_ID))
            }
            Event.CONTENT_ITEM_TYPE -> {
                if (!supported(row)) return false
                val json = JSONObject()
                fields(listOf(row)).forEach { (key, value) -> json.put(key, JSONArray(value)) }
                rows(Person("", json)).singleOrNull() ?: return false
            }
            else -> {
                val spec = specs.firstOrNull { it.mime == mime } ?: return false
                fromJson(spec, toJson(spec, row))
            }
        }
        val projected = ContentValues(reconstructed).apply { normalize(mime, reconstructed)?.let(::putAll) }
        val undefinedStyles = when (mime) {
            // Full-name style is data10; its named constant is public only from API 21.
            StructuredName.CONTENT_ITEM_TYPE -> setOf(Data.DATA10, StructuredName.PHONETIC_NAME_STYLE)
            Organization.CONTENT_ITEM_TYPE -> setOf(Organization.PHONETIC_NAME_STYLE)
            else -> emptySet()
        }
        val flags = setOf(Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY, Data.IS_READ_ONLY)
        val defaults = flags + undefinedStyles
        val columns = (1..15).map { "data$it" } + flags + listOf(Data.SYNC1, Data.SYNC2, Data.SYNC3, Data.SYNC4)
        return columns.all { column ->
            val value = row.get(column)
            when {
                value == null || value == "" || value is ByteArray && value.isEmpty() -> true
                // Switch writes undefined name styles (0); unset flags also carry no preference.
                column in defaults && row.getAsString(column) == "0" -> true
                // Permit only an exactly redundant normalized phone number, not guessed country/format equivalence.
                mime == Phone.CONTENT_ITEM_TYPE && column == Phone.NORMALIZED_NUMBER && value is String && value == row.getAsString(Phone.NUMBER) -> true
                value is ByteArray -> (projected.get(column) as? ByteArray)?.contentEquals(value) == true
                else -> row.getAsString(column) == projected.getAsString(column)
            }
        }
    }

    private fun fromJson(spec: Spec, json: JSONObject): ContentValues = ContentValues().apply {
        put(Data.MIMETYPE, spec.mime)
        for ((key, column) in spec.columns) if (json.has(key)) put(column, json.optString(key))
        if (spec.field == "names" && spec.columns.keys.none { json.optString(it).isNotEmpty() }) {
            put(StructuredName.DISPLAY_NAME, json.optString("unstructuredName", json.optString("displayName")))
        }
        if (spec.typeColumn != null) {
            val type = json.optString("type", "other")
            put(spec.typeColumn, spec.types[type] ?: 0)
            if (type !in spec.types) put(spec.labelColumn, type)
        }
        if (spec.field == "imClients") {
            val protocol = json.optString("protocol")
            put(Im.PROTOCOL, imProtocols[protocol] ?: Im.PROTOCOL_CUSTOM)
            if (protocol !in imProtocols) put(Im.CUSTOM_PROTOCOL, protocol)
        }
        if (spec.field in primaryFields) put(Data.IS_PRIMARY, if (json.optJSONObject("metadata")?.optBoolean("sourcePrimary") == true) 1 else 0)
    }
    private fun toJson(spec: Spec, values: ContentValues): JSONObject = JSONObject().apply {
        for ((key, column) in spec.columns) values.getAsString(column)?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
        if (spec.field == "names" && length() == 0) values.getAsString(StructuredName.DISPLAY_NAME)
            ?.takeIf { it.isNotEmpty() }?.let { put("unstructuredName", it) }
        if (spec.typeColumn != null) {
            val type = values.getAsInteger(spec.typeColumn) ?: 0
            put("type", spec.types.entries.firstOrNull { it.value == type }?.key
                ?: values.getAsString(spec.labelColumn).orEmpty().ifEmpty { "other" })
        }
        if (spec.field == "imClients") {
            val protocol = values.getAsInteger(Im.PROTOCOL)
            put("protocol", imProtocols.entries.firstOrNull { it.value == protocol }?.key
                ?: values.getAsString(Im.CUSTOM_PROTOCOL).orEmpty())
        }
        if (spec.field == "biographies") put("contentType", "TEXT_PLAIN")
        if (spec.field in primaryFields) put("metadata", JSONObject().put("sourcePrimary", values.getAsInteger(Data.IS_PRIMARY) == 1))
    }

    fun rows(person: Person): List<ContentValues> {
        val result = mutableListOf<ContentValues>()
        for (spec in specs) {
            val array = person.json.optJSONArray(spec.field) ?: continue
            for (i in 0 until array.length()) result.add(fromJson(spec, array.getJSONObject(i)))
        }
        for (field in listOf("birthdays", "events")) person.json.optJSONArray(field)?.let { array ->
            for (i in 0 until array.length()) array.getJSONObject(i).let { event ->
                val date = event.optJSONObject("date") ?: return@let
                val value = ContactDates.format(date) ?: return@let
                result.add(ContentValues().apply {
                    put(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                    val type = event.optString("type", "other")
                    put(Event.TYPE, if (field == "birthdays") Event.TYPE_BIRTHDAY else eventTypes[type] ?: Event.TYPE_CUSTOM)
                    if (field == "events" && type !in eventTypes) put(Event.LABEL, type)
                    put(Event.START_DATE, value)
                })
            }
        }
        return result
    }
    fun fields(rows: List<ContentValues>): Fields {
        val result = PeopleApi.EDITABLE_FIELDS.associateWith { JSONArray() }
        for (row in rows) {
            val spec = specs.firstOrNull { it.mime == row.getAsString(Data.MIMETYPE) }
            if (spec != null) result.getValue(spec.field).put(toJson(spec, row))
            else if (row.getAsString(Data.MIMETYPE) == Event.CONTENT_ITEM_TYPE) {
                val type = row.getAsInteger(Event.TYPE)
                ContactDates.parse(row.getAsString(Event.START_DATE).orEmpty())?.let { date ->
                    val event = JSONObject().put("date", date)
                    val field = if (type == Event.TYPE_BIRTHDAY) "birthdays" else "events"
                    if (field == "events") event.put("type", eventTypes.entries.firstOrNull { it.value == type }?.key
                        ?: row.getAsString(Event.LABEL).orEmpty().ifEmpty { "other" })
                    result.getValue(field).put(event)
                }
            }
        }
        return result.mapValues { (_, array) ->
            // Provider row order is not stable across edits and batch inserts.
            val sorted = (0 until array.length()).map { canonical(array.get(it)) }.sorted()
            "[" + sorted.joinToString(",") + "]"
        }
    }
    fun readRows(cursor: Cursor): List<ContentValues> = buildList {
        while (cursor.moveToNext()) add(ContentValues().apply {
            for (name in cursor.columnNames) {
                val index = cursor.getColumnIndex(name)
                if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) put(name, cursor.getLong(index))
                else if (cursor.getType(index) == Cursor.FIELD_TYPE_STRING) put(name, cursor.getString(index))
                else if (cursor.getType(index) == Cursor.FIELD_TYPE_FLOAT) put(name, cursor.getDouble(index))
                else if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) put(name, cursor.getBlob(index))
            }
        })
    }
}
