/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocationAppsDatabaseTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: LocationAppsDatabase
    @Volatile private var beforeCount: (() -> Unit)? = null
    @Volatile private var lastCursor: SQLiteCursor? = null

    @Before
    fun setUp() {
        context.deleteDatabase("geoapps.db")
        database = LocationAppsDatabase(context)
        // Install a factory on the real helper without adding a production test hook.
        database.setOpenParams(SQLiteDatabase.OpenParams.Builder()
            .setCursorFactory { _, driver, editTable, query ->
                object : SQLiteCursor(driver, editTable, query) {
                    override fun getCount(): Int {
                        val callback = beforeCount
                        beforeCount = null
                        callback?.invoke()
                        return super.getCount()
                    }
                }.also { lastCursor = it }
            }.build())
        database.noteAppLocation("test.location", Location("test").apply {
            latitude = 37.0
            longitude = -122.0
            accuracy = 10f
            time = 1000L
        })
        // Open the database before arming the read barrier.
        database.readableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("geoapps.db")
    }

    @Test
    fun lifecycleCloseWaitsForRecentAppsToBeMaterialized() {
        assertReadCompletesBefore({ database.listAppsByAccessTime() }, { database.close() }) {
            assertEquals(listOf("test.location"), it.map { app -> app.first })
        }
        // Pausing does not make the helper unusable on the next resume.
        assertEquals("test.location", database.listAppsByAccessTime().single().first)
    }

    @Test
    fun lifecycleCloseWaitsForCoarseSettingRead() {
        assertReadCompletesBefore({ database.getForceCoarse("test.location") }, { database.close() }) {
            assertFalse(it)
        }
    }

    @Test
    fun lifecycleCloseWaitsForLastLocationRead() {
        assertReadCompletesBefore({ database.getAppLocation("test.location") }, { database.close() }) {
            assertEquals(37.0, it!!.latitude, 0.0)
            assertEquals(1000L, it.time)
        }
    }

    @Test
    fun usageWriteCannotCloseARecentAppsQuery() {
        assertReadCompletesBefore({ database.listAppsByAccessTime() }, { database.noteAppUsage("test.other") }) {
            assertEquals(listOf("test.location"), it.map { app -> app.first })
        }
        assertEquals(setOf("test.location", "test.other"), database.listAppsByAccessTime().map { it.first }.toSet())
    }

    @Test
    fun coarseSettingWriteCannotCloseARecentAppsQuery() {
        assertReadCompletesBefore({ database.listAppsByAccessTime() }, { database.setForceCoarse("test.location", true) }) {
            assertEquals("test.location", it.single().first)
        }
        assertTrue(database.getForceCoarse("test.location"))
    }

    @Test
    fun locationWriteCannotCloseARecentAppsQuery() {
        assertReadCompletesBefore({ database.listAppsByAccessTime() }, {
            database.noteAppLocation("test.other", Location("test").apply { time = 2000L })
        }) {
            assertEquals("test.location", it.single().first)
        }
        assertEquals(2000L, database.getAppLocation("test.other")!!.time)
    }

    @Test
    fun failedCursorReadClosesTheCursorAndPropagatesTheFailure() {
        val failure = IllegalStateException("synthetic cursor failure")
        beforeCount = { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { database.listAppsByAccessTime() })
        assertTrue(lastCursor!!.isClosed)
        database.close()
        assertEquals("test.location", database.listAppsByAccessTime().single().first)
    }

    @Test
    fun materializedResultsRespectOrderingAndLimitAfterClose() {
        database.writableDatabase.execSQL("INSERT INTO apps(package, time) VALUES ('test.older', 1)")
        val apps = database.listAppsByAccessTime(1)
        database.close()
        assertEquals(listOf("test.location"), apps.map { it.first })
        assertEquals(listOf("test.location", "test.older"), database.listAppsByAccessTime().map { it.first })
    }

    private fun <T> assertReadCompletesBefore(read: () -> T, competingOperation: () -> Unit, verify: (T) -> Unit) {
        val reading = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        val competingStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        beforeCount = {
            reading.countDown()
            check(resumeRead.await(10, SECONDS)) { "cursor read was not released" }
        }
        try {
            val reader = executor.submit(Callable { read() })
            assertTrue("reader did not reach the cursor", reading.await(10, SECONDS))
            val competing = executor.submit {
                competingStarted.countDown()
                competingOperation()
            }
            assertTrue(competingStarted.await(10, SECONDS))
            val completedEarly = try {
                competing.get(150, MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }
            resumeRead.countDown()
            verify(reader.get(10, SECONDS))
            competing.get(10, SECONDS)
            assertFalse("operation completed while a cursor was still being read", completedEarly)
            assertTrue(lastCursor!!.isClosed)
        } finally {
            resumeRead.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, SECONDS))
        }
    }

}
