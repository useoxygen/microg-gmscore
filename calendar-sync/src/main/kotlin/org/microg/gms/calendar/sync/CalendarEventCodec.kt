/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.json.JSONArray
import org.json.JSONObject

/** Versioned provider journal/baseline encoding, independent of Google's response and Android's columns. */
object CalendarEventCodec {
    private fun time(value: EventTime?) = value?.let {
        JSONObject().put("millis", it.millis).put("allDay", it.allDay).put("zone", it.zone)
    } ?: JSONObject.NULL
    private fun time(value: JSONObject?): EventTime? = value?.let { EventTime(it.getLong("millis"), it.getBoolean("allDay"), it.getString("zone")) }
    fun encode(value: RemoteEvent): JSONObject = JSONObject().put("version", 1)
        .put("id", value.id).put("etag", value.etag).put("title", value.title).put("description", value.description)
        .put("location", value.location).put("start", time(value.start)).put("end", time(value.end))
        .put("recurrence", JSONArray(value.recurrence)).put("masterId", value.masterId ?: JSONObject.NULL)
        .put("original", time(value.original)).put("cancelled", value.cancelled).put("reminders", JSONArray(value.reminders))
        .put("sequence", value.sequence).put("useDefaultReminders", value.useDefaultReminders)
        .put("creationId", value.creationId ?: JSONObject.NULL).put("raw", value.raw ?: JSONObject.NULL)
        .put("visibility", value.visibility).put("transparency", value.transparency)
    fun decode(value: JSONObject): RemoteEvent {
        require(value.getInt("version") == 1)
        fun string(key: String) = if (value.isNull(key)) null else value.getString(key)
        val recurrence = value.getJSONArray("recurrence"); val reminders = value.getJSONArray("reminders")
        return RemoteEvent(value.getString("id"), value.getString("etag"), value.getString("title"), value.getString("description"),
            value.getString("location"), time(value.optJSONObject("start")), time(value.optJSONObject("end")),
            (0 until recurrence.length()).map { recurrence.getString(it) }, string("masterId"), time(value.optJSONObject("original")),
            value.getBoolean("cancelled"), (0 until reminders.length()).map { reminders.getInt(it) }, value.getInt("sequence"),
            value.getBoolean("useDefaultReminders"), string("creationId"), string("raw"),
            value.optString("visibility", "default"), value.optString("transparency", "opaque"))
    }
}
