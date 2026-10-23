package org.microg.gms.maps.mapbox

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap

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
    // Live maps to restyle when the key becomes rejected. Owners are weak keys and callbacks get the owner passed in
    // (they must not capture it), so a map that is never destroyed isn't kept alive.
    private val subscribers = WeakHashMap<Any, (Any) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * [onRejected] runs on the main thread with [owner] each time the key turns from working to rejected, until
     * [unsubscribe]. It must not capture [owner].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> subscribe(owner: T, onRejected: (T) -> Unit) {
        if (BuildConfig.STADIA_KEY.isEmpty()) return
        synchronized(this) { subscribers[owner] = onRejected as (Any) -> Unit }
        check()
    }

    fun unsubscribe(owner: Any) {
        synchronized(this) { subscribers.remove(owner) }
    }

    /** Starts a check unless one is running or the last answer is recent. */
    fun check() {
        if (BuildConfig.STADIA_KEY.isEmpty()) return
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (running || (checkedAt != 0L && now - checkedAt < RECHECK_MS)) return
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
            } catch (e: Exception) { // IOException, or SecurityException in an app without INTERNET
                Log.d(TAG, "Tile key check failed: $e")
                null
            }
            val notify: List<Pair<Any, (Any) -> Unit>>
            synchronized(this) {
                running = false
                val wasRejected = stadiaRejected
                if (code != null) {
                    checkedAt = SystemClock.elapsedRealtime()
                    stadiaRejected = code in REJECTED
                }
                notify = if (stadiaRejected && !wasRejected) subscribers.entries.map { it.key to it.value } else emptyList()
            }
            if (code in REJECTED) Log.w(TAG, "Stadia rejected the tile key (HTTP $code); using OpenFreeMap")
            notify.forEach { (owner, callback) -> main.post { callback(owner) } }
        }, "GmsMapTileKeyCheck").start()
    }
}
