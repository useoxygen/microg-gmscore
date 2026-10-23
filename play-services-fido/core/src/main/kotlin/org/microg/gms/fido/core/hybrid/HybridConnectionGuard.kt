/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

/** Atomic connection generation: a completed handshake cannot resurrect a socket closed during crypto or send. */
internal class HybridConnectionGuard {
    private data class State(val generation: Long, val live: Boolean, val used: Boolean)
    private val gate = Any()
    @Volatile private var state = State(0, false, false)
    fun connected() = synchronized(gate) {
        check(!state.used)
        state = State(state.generation + 1, true, true)
    }
    fun capture(): Long = state.let { check(it.live); it.generation }
    fun live(generation: Long): Boolean = state.let { it.live && it.generation == generation }
    fun <T> install(generation: Long, action: () -> T): T = synchronized(gate) {
        check(live(generation))
        action()
    }
    fun invalidate() = synchronized(gate) { state = State(state.generation + 1, false, true) }
}
