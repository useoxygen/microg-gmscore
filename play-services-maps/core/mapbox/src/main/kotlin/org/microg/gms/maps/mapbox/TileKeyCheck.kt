package org.microg.gms.maps.mapbox

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks, once per process and at most every [RECHECK_MS], whether Stadia still serves tiles for our key. A rejected
 * key (invalid, unpaid or over quota) switches every map to the keyless OpenFreeMap style instead of blank tiles.
 * Network failures don't count: OpenFreeMap would be unreachable too.
 */
object TileKeyCheck {
    private const val TAG = "GmsMapTileKey"
    private const val RECHECK_MS = 30 * 60 * 1000L
    private const val PROBE_URL = "https://tiles.stadiamaps.com/data/openmaptiles/0/0/0.pbf?api_key="
    private val REJECTED = setOf(401, 402, 403, 429)

    @Volatile
    var stadiaRejected = false
        private set
    private var checkedAt = 0L
    private var running = false
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Starts a check if due; [onRejected] runs on the main thread if this check finds the key rejected. */
    fun check(onRejected: (() -> Unit)? = null) {
        if (BuildConfig.STADIA_KEY.isEmpty()) return
        synchronized(this) {
            onRejected?.let { listeners += it }
            val now = SystemClock.elapsedRealtime()
            if (running || (checkedAt != 0L && now - checkedAt < RECHECK_MS)) {
                if (!running) listeners.clear()
                return
            }
            running = true
        }
        Thread({
            val code = try {
                val connection = URL(PROBE_URL + BuildConfig.STADIA_KEY).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Log.d(TAG, "Tile key check failed: $e")
                null
            }
            val rejected = code in REJECTED
            val wasRejected = stadiaRejected
            val notify: List<() -> Unit>
            synchronized(this) {
                running = false
                if (code != null) {
                    checkedAt = SystemClock.elapsedRealtime()
                    stadiaRejected = rejected
                }
                notify = if (rejected && !wasRejected) listeners.toList() else emptyList()
                listeners.clear()
            }
            if (rejected) Log.w(TAG, "Stadia rejected the tile key (HTTP $code); using OpenFreeMap")
            notify.forEach { main.post(it) }
        }, "GmsMapTileKeyCheck").start()
    }
}
