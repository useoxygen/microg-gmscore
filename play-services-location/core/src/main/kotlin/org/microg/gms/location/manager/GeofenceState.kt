/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

/** Transition state uses monotonic time; an inaccurate boundary reading never invents an enter/exit. */
internal data class GeofenceState(val inside: Boolean? = null, val enteredAt: Long = 0, val dwelled: Boolean = false, val dwellArmed: Boolean = false)
internal data class GeofenceStep(val state: GeofenceState, val transitions: List<Int>)
internal fun geofenceStep(state: GeofenceState, distance: Float, accuracy: Float, radius: Float, now: Long,
    transitions: Int, initial: Int, dwell: Int): GeofenceStep {
    if (!distance.isFinite() || !accuracy.isFinite() || accuracy < 0 || radius <= 0) return GeofenceStep(state, emptyList())
    val inside = when {
        distance + accuracy <= radius -> true
        distance - accuracy > radius -> false
        else -> return GeofenceStep(state, emptyList())
    }
    val changed = state.inside != inside
    val entered = if (inside && changed) now else state.enteredAt
    var dwelled = if (changed) false else state.dwelled
    val result = mutableListOf<Int>()
    if (changed) {
        val transition = if (inside) 1 else 2
        if (transitions and transition != 0 && (state.inside != null || initial and transition != 0)) result += transition
    }
    // Without INITIAL_TRIGGER_DWELL, an initially-inside registration must wait for a later re-entry.
    val dwellEligible = if (changed) inside && (state.inside != null || initial and 4 != 0) else state.dwellArmed
    if (inside && !dwelled && dwellEligible && transitions and 4 != 0 && now - entered >= dwell.coerceAtLeast(0)) {
        result += 4; dwelled = true
    }
    return GeofenceStep(GeofenceState(inside, entered, dwelled, dwellEligible), result)
}
