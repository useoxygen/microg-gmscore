/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager
import org.junit.Test
import org.junit.Assert.*
class GeofenceStateTest {
    private fun step(state: GeofenceState, distance: Float, now: Long, initial: Int = 5, accuracy: Float = 5f) =
        geofenceStep(state, distance, accuracy, 100f, now, 7, initial, 30_000)
    @Test fun enterDwellExitAreEmittedOnce() {
        val entered = step(GeofenceState(), 10f, 1_000)
        assertEquals(listOf(1), entered.transitions)
        val before = step(entered.state, 10f, 30_999); assertTrue(before.transitions.isEmpty())
        val dwell = step(before.state, 10f, 31_000); assertEquals(listOf(4), dwell.transitions)
        assertTrue(step(dwell.state, 10f, 60_000).transitions.isEmpty())
        assertEquals(listOf(2), step(dwell.state, 200f, 61_000).transitions)
    }
    @Test fun uncertainBoundaryDoesNotFlipOrResetDwell() {
        val entered = step(GeofenceState(), 10f, 1_000)
        val uncertain = step(entered.state, 95f, 20_000, accuracy = 20f)
        assertEquals(entered.state, uncertain.state); assertTrue(uncertain.transitions.isEmpty())
    }
    @Test fun initialFlagsDoNotCreateUnrequestedTransitions() {
        val first = step(GeofenceState(), 10f, 1_000, initial = 0)
        assertTrue(first.transitions.isEmpty())
        assertTrue(step(first.state, 10f, 61_000, initial = 0).transitions.isEmpty())
        val exit = step(first.state, 200f, 62_000, initial = 0)
        val reenter = step(exit.state, 10f, 63_000, initial = 0)
        assertEquals(listOf(4), step(reenter.state, 10f, 93_000, initial = 0).transitions)
    }
    @Test fun invalidAccuracyDoesNotProduceEvents() {
        assertTrue(step(GeofenceState(), 0f, 0, accuracy = Float.NaN).transitions.isEmpty())
        assertTrue(step(GeofenceState(), 0f, 0, accuracy = -1f).transitions.isEmpty())
    }
}
