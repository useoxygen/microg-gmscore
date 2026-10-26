/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.microg.gms.migration.session.DctSession

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctHandoffBufferTest {
    @Test fun notificationsSurviveSetupToSwitchListenerGapInOrder() {
        val controller = DctController(RuntimeEnvironment.getApplication(), DctSession.create())
        val values = mutableListOf<Int>()
        controller.add(object : DctController.Listener {}) // setup has no application sink
        controller.oneWay("application/test", byteArrayOf(4))
        controller.oneWay("application/test", byteArrayOf(2))
        val switch = object : DctController.Listener {
            override fun oneWay(type: String, bytes: ByteArray): Boolean { values.add(bytes.single().toInt()); return true }
        }
        controller.add(switch); controller.applicationReady(switch)
        controller.oneWay("application/test", byteArrayOf(1))
        assertEquals(listOf(4,2,1), values); controller.close()
    }
    @Test fun boundedHandoffQueueFailsInsteadOfAcknowledgingUnboundedUndeliveredData() {
        val controller = DctController(RuntimeEnvironment.getApplication(), DctSession.create())
        var closed = 0
        controller.add(object : DctController.Listener { override fun closed(status: Int) { closed = status } })
        repeat(9) { controller.oneWay("application/test", byteArrayOf(1)) }
        assertEquals(8013, closed); assertTrue(controller.isClosed)
    }
}
