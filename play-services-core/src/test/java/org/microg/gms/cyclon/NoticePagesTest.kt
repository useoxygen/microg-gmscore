/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cyclon

import org.junit.Assert.*
import org.junit.Test

class NoticePagesTest {
    @Test fun longProducerBundleRetainsAllTextWithBoundedPages() {
        val text = "Original copyright and license.\n".repeat(30_000)
        val pages = noticePages(text)
        assertTrue(pages.size > 100)
        assertTrue(pages.all { it.isNotEmpty() && it.length <= 8_000 })
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun surrogatePairsAreNotSplitAtThePageBoundary() {
        val text = "a".repeat(7_999) + "\uD83C\uDF00" + "b".repeat(8_000)
        val pages = noticePages(text)
        assertFalse(pages.first().last().isHighSurrogate())
        assertFalse(pages[1].first().isLowSurrogate())
        assertEquals(text, pages.joinToString(""))
    }

    @Test fun shortAndEmptyNoticesRemainOnePage() {
        assertEquals(listOf(""), noticePages(""))
        assertEquals(listOf("Copyright\nLicense"), noticePages("Copyright\nLicense"))
        assertEquals(listOf("a".repeat(8_000)), noticePages("a".repeat(8_000)))
    }
}
