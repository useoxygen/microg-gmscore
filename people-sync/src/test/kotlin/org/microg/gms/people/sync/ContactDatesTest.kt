/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ContactDatesTest {
    @Test fun isoAndYearlessDatesRoundTrip() {
        for (text in listOf("1980-01-02", "2000-02-29", "--02-29")) {
            assertEquals(text, ContactDates.format(ContactDates.parse(text)!!))
        }
        for (text in listOf("2001-02-29", "--04-31", "--00-01", "bad", "1980-1-2")) assertNull(ContactDates.parse(text))
        assertNull(ContactDates.format(JSONObject().put("year", 1980)))
    }
    @Test fun serializedDatesRemainAsciiInNonLatinLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("--03-07", ContactDates.format(JSONObject().put("month", 3).put("day", 7)))
        } finally { Locale.setDefault(previous) }
    }
}
