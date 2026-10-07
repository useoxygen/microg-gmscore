/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import android.Manifest
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.*
import com.google.android.gms.location.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.microg.gms.utils.IntentCacheManager

/** App-scoped registration, fused sampling and same-boot recovery through the existing intent cache. */
internal class GeofenceManager(private val context: Context, private val locations: LocationManager,
    override val lifecycle: Lifecycle) : LifecycleOwner {
    private data class Entry(val owner: ClientIdentity, val intent: PendingIntent, val fence: ParcelableGeofence,
        val initial: Int, var state: GeofenceState = GeofenceState())
    private val entries = mutableMapOf<Pair<String, String>, Entry>()
    private val bindings = mutableMapOf<String, IBinder>()
    private val bindingIntervals = mutableMapOf<String, Long>()
    private val cache by lazy { IntentCacheManager.create<LocationManagerService, Bundle>(context, CACHE_TYPE) }
    private val ready = CompletableDeferred<Unit>()
    private val lock = Mutex()
    private var started = false
    private var holdingService = false
    private var ticker: Job? = null
    private val lastLocations = mutableMapOf<String, Location>()
    fun start() {
        if (started) return
        started = true
        cache
    }
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = lifecycleScope.launchWhenStarted {
            while (isActive) {
                delay(10_000)
                lock.withLock {
                    val now = SystemClock.elapsedRealtime()
                    val blocked = entries.values.map { it.owner }.distinctBy { it.packageName }.filterNot { permitted(it) }.map { it.packageName }.toSet()
                    val ownAllowed = ownPermission()
                    val expired = entries.filter { !ownAllowed || it.value.owner.packageName in blocked ||
                        (it.value.fence.expirationTime != -1L && it.value.fence.expirationTime <= now) }.keys
                    expired.toList().forEach { removeEntry(it) }
                    for ((owner, location) in lastLocations.toMap()) {
                        if (now - location.elapsedRealtimeNanos/1_000_000 <= 120_000) evaluate(owner, location, now)
                    }
                    refreshBindings()
                }
            }
        }
    }
    fun stop() { ticker?.cancel(); ticker = null; started = false }
    private fun ownPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        .plus(if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList())
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    private fun permitted(owner: ClientIdentity): Boolean {
        val pm = context.packageManager
        if (runCatching { pm.getApplicationInfo(owner.packageName, 0).uid }.getOrNull() != owner.uid) return false
        if (pm.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, owner.packageName) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 29 && pm.checkPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, owner.packageName) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, owner.uid, owner.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }
    suspend fun add(owner: ClientIdentity, request: GeofencingRequest?, intent: PendingIntent?): Int {
        start()
        withTimeout(5_000) { ready.await() }
        if (!permitted(owner)) return GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION
        if (!ownPermission()) return GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
        if (intent == null || intent.creatorUid != owner.uid || intent.creatorPackage != owner.packageName) return CommonStatusCodes.DEVELOPER_ERROR
        if (Build.VERSION.SDK_INT >= 31 && intent.isImmutable) return CommonStatusCodes.DEVELOPER_ERROR
        val fences = request?.geofences?.map { it as? ParcelableGeofence ?: return CommonStatusCodes.DEVELOPER_ERROR }
            ?: return CommonStatusCodes.DEVELOPER_ERROR
        if (fences.size > 100) return GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES
        if (fences.isEmpty() || fences.any {
                it.requestId.isNullOrBlank() || it.requestId.length > 100 || !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                    !it.longitude.isFinite() || it.longitude !in -180.0..180.0 || !it.radius.isFinite() || it.radius <= 0 ||
                    it.transitionTypes and 7 == 0 || it.transitionTypes and 7 != it.transitionTypes ||
                    (it.transitionTypes and 4 != 0 && it.loiteringDelay < 0) || it.regionType != 1
            }) return CommonStatusCodes.DEVELOPER_ERROR
        return lock.withLock {
            val ownerEntries = entries.values.filter { it.owner.packageName == owner.packageName }
            val replacement = fences.map { it.requestId }.toSet()
            if (ownerEntries.count { it.fence.requestId !in replacement } + replacement.size > 100 ||
                entries.size - ownerEntries.count { it.fence.requestId in replacement } + replacement.size > 500) return@withLock GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES
            if ((ownerEntries.filter { it.fence.requestId !in replacement }.map { it.intent } + intent).toSet().size > 5) return@withLock GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS
            val before = replacement.associateWith { entries[owner.packageName to it] }
            try {
                for (fence in fences) {
                    val key = owner.packageName to fence.requestId
                    entries[key] = Entry(owner, intent, fence, request!!.initialTrigger)
                    save(entries.getValue(key))
                }
                refreshBindings()
                CommonStatusCodes.SUCCESS
            } catch (_: Exception) {
                // A failed registration must not leave newly admitted fences behind.
                for ((id, previous) in before) {
                    removeEntry(owner.packageName to id)
                    if (previous != null) { entries[owner.packageName to id] = previous; save(previous) }
                }
                runCatching { refreshBindings() }
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
            }
        }
    }
    suspend fun remove(owner: ClientIdentity, request: RemoveGeofencingRequest?): Int {
        start(); withTimeout(5_000) { ready.await() }
        if (request == null) return CommonStatusCodes.DEVELOPER_ERROR
        if (request.pendingIntent?.let { it.creatorUid != owner.uid } == true) return CommonStatusCodes.DEVELOPER_ERROR
        return lock.withLock {
            entries.filter { it.value.owner.packageName == owner.packageName && it.value.owner.uid == owner.uid &&
                (it.value.fence.requestId in request.geofenceIds.orEmpty() || it.value.intent == request.pendingIntent) }.keys.toList().forEach { removeEntry(it) }
            refreshBindings(); CommonStatusCodes.SUCCESS
        }
    }
    suspend fun removeAll(owner: ClientIdentity): Int {
        start(); withTimeout(5_000) { ready.await() }
        return lock.withLock {
            entries.filter { it.value.owner.packageName == owner.packageName && it.value.owner.uid == owner.uid }.keys.toList().forEach { removeEntry(it) }
            refreshBindings(); CommonStatusCodes.SUCCESS
        }
    }
    private fun save(entry: Entry) {
        val bundle = Bundle().apply {
            putString("owner", entry.owner.packageName); putInt("uid", entry.owner.uid)
            putParcelable("intent", entry.intent); putParcelable("fence", entry.fence); putInt("initial", entry.initial)
            putInt("inside", when(entry.state.inside) { true -> 1; false -> 0; null -> -1 })
            putLong("entered", entry.state.enteredAt); putBoolean("dwelled", entry.state.dwelled)
            putBoolean("armed", entry.state.dwellArmed)
        }
        cache.add(bundle) { it.getString("owner") == entry.owner.packageName && it.getParcelable<ParcelableGeofence>("fence")?.requestId == entry.fence.requestId }
    }
    private fun removeEntry(key: Pair<String, String>) {
        entries.remove(key)
        cache.removeIf { it.getString("owner") == key.first && it.getParcelable<ParcelableGeofence>("fence")?.requestId == key.second }
    }
    fun handleCacheIntent(intent: Intent) {
        cache.processIntent(intent)
        lifecycleScope.launchWhenStarted {
            lock.withLock {
                for (row in cache.getEntries().take(500)) {
                    runCatching {
                        row.classLoader = ParcelableGeofence::class.java.classLoader
                        val owner = ClientIdentity(requireNotNull(row.getString("owner"))).apply { uid = row.getInt("uid"); pid = 0 }
                        val pending = requireNotNull(row.getParcelable<PendingIntent>("intent"))
                        val fence = requireNotNull(row.getParcelable<ParcelableGeofence>("fence"))
                        if (ownPermission() && permitted(owner) && pending.creatorUid == owner.uid && pending.creatorPackage == owner.packageName && (fence.expirationTime == -1L || fence.expirationTime > SystemClock.elapsedRealtime())) {
                            entries.putIfAbsent(owner.packageName to fence.requestId, Entry(owner, pending, fence, row.getInt("initial"),
                                GeofenceState(when(row.getInt("inside", -1)) { 1 -> true; 0 -> false; else -> null }, row.getLong("entered"), row.getBoolean("dwelled"), row.getBoolean("armed"))))
                        } else cache.remove(row)
                    }.onFailure { cache.remove(row) }
                }
                refreshBindings()
                ready.complete(Unit)
            }
        }
    }
    private suspend fun refreshBindings() {
        if (entries.isNotEmpty()) startTicker() else { ticker?.cancel(); ticker = null }
        val owners = entries.values.map { it.owner }.distinctBy { it.packageName }
        for (name in bindings.keys.filter { name -> owners.none { it.packageName == name } }.toList()) {
            locations.removeBinderRequest(bindings.remove(name)!!); lastLocations.remove(name); bindingIntervals.remove(name)
        }
        for (owner in owners) {
            val interval = entries.values.filter { it.owner.packageName == owner.packageName }.minOf {
                if (it.fence.notificationResponsiveness <= 0) 60_000L else it.fence.notificationResponsiveness.toLong().coerceIn(5_000, 300_000)
            }
            if (bindings.containsKey(owner.packageName) && bindingIntervals[owner.packageName] == interval) continue
            bindings.remove(owner.packageName)?.let { locations.removeBinderRequest(it) }
            val binder = Binder()
            val callback = object : ILocationCallback.Stub() {
                override fun onLocationResult(result: LocationResult?) {
                    val location = result?.lastLocation ?: return
                    lifecycleScope.launchWhenStarted { lock.withLock {
                        lastLocations[owner.packageName] = location
                        evaluate(owner.packageName, location, SystemClock.elapsedRealtime())
                    } }
                }
                override fun onLocationAvailability(availability: LocationAvailability?) = Unit
                override fun cancel() { lifecycleScope.launchWhenStarted { lock.withLock {
                    // Removing a superseded sampling request also cancels its callback. Only
                    // cancellation of the current request may discard this owner's fences.
                    if (bindings[owner.packageName] !== binder) return@withLock
                    entries.filter { it.value.owner.packageName == owner.packageName }.keys.toList().forEach { removeEntry(it) }
                    refreshBindings()
                } } }
            }
            try {
                locations.addBinderRequest(owner, binder, callback, LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                    .setGranularity(Granularity.GRANULARITY_FINE).setMaxUpdateAgeMillis(0).build())
                bindings[owner.packageName] = binder
                bindingIntervals[owner.packageName] = interval
            } catch (e: Exception) { locations.removeBinderRequest(binder); throw e }
        }
        // Registrations outlive the registering client's Binder connection. Starting the existing
        // service also permits same-boot intent-cache recovery after process recreation.
        if (entries.isNotEmpty() && !holdingService) {
            context.startService(Intent(context, GoogleLocationManagerService::class.java))
            holdingService = true
        } else if (entries.isEmpty() && holdingService) {
            context.stopService(Intent(context, GoogleLocationManagerService::class.java))
            holdingService = false
        }
    }
    private fun evaluate(owner: String, location: Location, now: Long) {
        val grouped = mutableMapOf<Pair<PendingIntent, Int>, MutableList<ParcelableGeofence>>()
        val candidates = entries.filter { it.value.owner.packageName == owner }.toMap()
        if (!ownPermission() || candidates.values.firstOrNull()?.let { !permitted(it.owner) } == true) {
            candidates.keys.forEach { removeEntry(it) }; return
        }
        for ((key, entry) in candidates) {
            if (entry.fence.expirationTime != -1L && entry.fence.expirationTime <= now) { removeEntry(key); continue }
            if (now - location.elapsedRealtimeNanos/1_000_000 !in 0..120_000 || !location.hasAccuracy()) continue
            val distances = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, entry.fence.latitude, entry.fence.longitude, distances)
            val step = geofenceStep(entry.state, distances[0], location.accuracy, entry.fence.radius, now,
                entry.fence.transitionTypes, entry.initial, entry.fence.loiteringDelay)
            if (step.state != entry.state) { entry.state = step.state; save(entry) }
            for (transition in step.transitions) grouped.getOrPut(entry.intent to transition) { mutableListOf() }.add(entry.fence)
        }
        for ((destination, fences) in grouped) {
            val event = Intent().putExtra(GeofencingEvent.EXTRA_TRANSITION, destination.second)
                .putExtra(GeofencingEvent.EXTRA_TRIGGERING_LOCATION, location)
                .putExtra(GeofencingEvent.EXTRA_GEOFENCE_LIST, ArrayList(fences.map { SafeParcelableSerializer.serializeToBytes(it) }))
            try { destination.first.send(context, 0, event) } catch (_: PendingIntent.CanceledException) {
                entries.filter { it.value.intent == destination.first }.keys.toList().forEach { removeEntry(it) }
            }
        }
    }
    companion object { const val CACHE_TYPE = 0x43594746 }
}
