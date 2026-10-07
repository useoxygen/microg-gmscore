/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cyclon

/** Bound text layout in the settings host while retaining every original character. */
internal fun noticePages(text: String, limit: Int = 8_000): List<String> {
    require(limit >= 2)
    if (text.isEmpty()) return listOf("")
    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            // Prefer a nearby paragraph/line boundary without producing tiny pages.
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start + limit / 2) end = newline + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        pages += text.substring(start, end)
        start = end
    }
    return pages
}
