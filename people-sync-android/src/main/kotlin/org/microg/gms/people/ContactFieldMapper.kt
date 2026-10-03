/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.ContactsContract.Data
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.people.sync.Fields
import org.microg.gms.people.sync.PeopleApi
import org.microg.gms.people.sync.Person
import org.microg.gms.people.sync.canonical

/** Only mapped fields enter an update mask. Other cloud fields remain untouched. */
object ContactFieldMapper {
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
        Spec("urls", Website.CONTENT_ITEM_TYPE, mapOf("value" to Website.URL), Website.TYPE, Website.LABEL,
            mapOf("home" to Website.TYPE_HOME, "work" to Website.TYPE_WORK, "blog" to Website.TYPE_BLOG,
                "profile" to Website.TYPE_PROFILE, "other" to Website.TYPE_OTHER))
    )
    val managedMimes = specs.map { it.mime }.toSet() + Event.CONTENT_ITEM_TYPE
    private val primaryFields = setOf("phoneNumbers", "emailAddresses", "addresses", "organizations", "nicknames", "urls")
    private fun date(text: String): JSONObject? {
        val parts = if (text.startsWith("--")) listOf("0") + text.removePrefix("--").split('-') else text.split('-')
        if (parts.size != 3 || parts.any { it.toIntOrNull() == null }) return null
        val year = parts[0].toInt(); val month = parts[1].toInt(); val day = parts[2].toInt()
        if (year < 0 || month !in 1..12 || day !in 1..31) return null
        return JSONObject().put("year", year).put("month", month).put("day", day)
    }
    fun supported(row: ContentValues): Boolean {
        val mime = row.getAsString(Data.MIMETYPE)
        if (mime == Event.CONTENT_ITEM_TYPE) return row.getAsInteger(Event.TYPE) == Event.TYPE_BIRTHDAY && date(row.getAsString(Event.START_DATE).orEmpty()) != null
        return mime in specs.map { it.mime }
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
        if (spec.field == "biographies") put("contentType", "TEXT_PLAIN")
        if (spec.field in primaryFields) put("metadata", JSONObject().put("sourcePrimary", values.getAsInteger(Data.IS_PRIMARY) == 1))
    }

    fun rows(person: Person): List<ContentValues> {
        val result = mutableListOf<ContentValues>()
        for (spec in specs) {
            val array = person.json.optJSONArray(spec.field) ?: continue
            for (i in 0 until array.length()) result.add(fromJson(spec, array.getJSONObject(i)))
        }
        person.json.optJSONArray("birthdays")?.let { array ->
            for (i in 0 until array.length()) array.getJSONObject(i).optJSONObject("date")?.let { date ->
                val year = date.optInt("year")
                if (date.optInt("month") !in 1..12 || date.optInt("day") !in 1..31) return@let
                val value = if (year > 0) "%04d-%02d-%02d".format(year, date.getInt("month"), date.getInt("day"))
                    else "--%02d-%02d".format(date.getInt("month"), date.getInt("day"))
                result.add(ContentValues().apply {
                    put(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                    put(Event.TYPE, Event.TYPE_BIRTHDAY)
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
            else if (row.getAsString(Data.MIMETYPE) == Event.CONTENT_ITEM_TYPE && row.getAsInteger(Event.TYPE) == Event.TYPE_BIRTHDAY) {
                val text = row.getAsString(Event.START_DATE).orEmpty()
                date(text)?.let { result.getValue("birthdays").put(JSONObject().put("date", it)) }
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
            }
        })
    }
}
