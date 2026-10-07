/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.droidguard.core

import org.junit.Assert.*
import org.junit.Test

class GuardExecutionTest {
    @Test fun resultIsDeliveredOnceAfterClosing() {
        val calls = mutableListOf<String>()
        executeGuard({ calls.add("init") }, { calls.add("snapshot"); byteArrayOf(1, 2) },
            { calls.add("close") }, { assertArrayEquals(byteArrayOf(1, 2), it); calls.add("callback") })
        assertEquals(listOf("init", "snapshot", "close", "callback"), calls)
    }
    @Test fun initFailureClosesAndDoesNotExposeExceptionText() {
        var closed = false
        var callbacks = 0
        executeGuard({ throw IllegalStateException("private payload") }, { fail("snapshot after failed init"); byteArrayOf() },
            { closed = true }, { assertTrue(closed); assertEquals("ERROR : execution failed", it.decodeToString()); callbacks++ })
        assertEquals(1, callbacks)
    }
    @Test fun snapshotFailureStillCloses() {
        var closed = false
        executeGuard({}, { throw IllegalStateException() }, { closed = true }, { assertTrue(closed); assertTrue(it.decodeToString().startsWith("ERROR :")) })
    }
    @Test fun closeFailureDoesNotLoseSuccessfulResult() {
        executeGuard({}, { byteArrayOf(7) }, { throw IllegalStateException() }, { assertArrayEquals(byteArrayOf(7), it) })
    }
    @Test fun callbackFailureIsNotRetried() {
        var callbacks = 0
        try {
            executeGuard({}, { byteArrayOf() }, {}, { callbacks++; throw IllegalStateException() })
            fail("callback failure should propagate to delivery boundary")
        } catch (_: IllegalStateException) { }
        assertEquals(1, callbacks)
    }
}
