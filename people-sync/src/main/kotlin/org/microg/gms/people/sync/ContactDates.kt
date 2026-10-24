/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONObject
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** ContactsProvider date strings use ASCII ISO dates, with --MM-dd for yearless dates. */
object ContactDates {
    fun format(date: JSONObject): String? {
        val year = date.optInt("year")
        val month = date.optInt("month")
        val day = date.optInt("day")
        if (year !in 0..9999 || month !in 1..12 || day !in 1..31) return null
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            gregorianChange = Date(Long.MIN_VALUE)
            isLenient = false
            clear()
            set(if (year == 0) 2000 else year, month - 1, day)
        }
        if (runCatching { calendar.timeInMillis }.isFailure) return null
        return if (year == 0) String.format(Locale.ROOT, "--%02d-%02d", month, day)
            else String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
    }
    fun parse(text: String): JSONObject? {
        if (!text.matches(Regex("(?:[0-9]{4}|-)-[0-9]{2}-[0-9]{2}"))) return null
        val parts = if (text.startsWith("--")) listOf("0") + text.drop(2).split('-') else text.split('-')
        val date = JSONObject().put("year", parts[0].toInt()).put("month", parts[1].toInt()).put("day", parts[2].toInt())
        return date.takeIf { format(it) != null }
    }
}
