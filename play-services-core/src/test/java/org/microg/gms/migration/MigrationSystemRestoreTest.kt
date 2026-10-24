/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.backup.RestoreObserver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationSystemRestoreTest {
    class Session {
        var restores = 0
        var ended = 0
        var startError = 0
        var observer: RestoreObserver? = null
        fun getAvailableRestoreSets(observer: RestoreObserver): Int {
            val type = Class.forName("android.app.backup.RestoreSet")
            val set = type.getConstructor(String::class.java, String::class.java, java.lang.Long.TYPE)
                .newInstance("Synthetic", "Synthetic", 1L)
            val array = java.lang.reflect.Array.newInstance(type, 1)
            java.lang.reflect.Array.set(array, 0, set)
            observer.javaClass.getDeclaredMethod("restoreSetsAvailable", array.javaClass).apply { isAccessible = true }
                .invoke(observer, array)
            return 0
        }
        fun restoreAll(token: Long, observer: RestoreObserver): Int {
            assertEquals(1L, token); restores++; this.observer = observer; return startError
        }
        fun endRestoreSession() { ended++ }
    }
    @Test fun successWaitsForPlatformCompletionAndLateCallbacksCannotOverwriteIt() {
        val context = RuntimeEnvironment.getApplication()
        MigrationArtifactStore(context).file("restore_token_file").writeText("1")
        val session = Session(); val results = mutableListOf<Boolean>()
        MigrationSystemRestore(context) { session }.start { results += it }
        assertEquals(1, session.restores); assertTrue(results.isEmpty())
        session.observer!!.restoreFinished(0)
        session.observer!!.restoreFinished(-1)
        assertEquals(listOf(true), results); assertEquals(1, session.ended)
    }
    @Test fun rejectedOrMissingPlatformSessionNeverSucceeds() {
        val context = RuntimeEnvironment.getApplication()
        MigrationArtifactStore(context).file("restore_token_file").writeText("1")
        val session = Session().apply { startError = -1 }; val results = mutableListOf<Boolean>()
        MigrationSystemRestore(context) { session }.start { results += it }
        assertEquals(listOf(false), results); assertEquals(1, session.ended)
        MigrationSystemRestore(context) { null }.start { results += it }
        assertEquals(listOf(false, false), results)
    }
}
