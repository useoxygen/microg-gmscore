/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

/** Only allowlisted observations cross into the UI or report; no raw errors or account identities. */
internal enum class HealthState { OFF, PAUSED, READY, CONNECTED, ATTENTION, UNKNOWN }
internal enum class HealthReason {
    DISABLED, UNREADABLE, CONNECTED, DISCONNECTED, NO_ACCOUNTS, SYNC_PAUSED,
    SYNC_PENDING, SYNC_SUCCESS, CONTACT_PERMISSION, AUTHORIZATION, NETWORK, LOCAL, CONFLICT, UNCERTAIN,
    LOCATION_READY, LOCATION_PERMISSION, REGISTRATION_PENDING, REGISTRATION_RECORDED, CALENDAR_PERMISSION
}
internal data class HealthReading(val state: HealthState, val reason: HealthReason, val lastSuccessAt: Long? = null)
internal val unknownHealth = HealthReading(HealthState.UNKNOWN, HealthReason.UNREADABLE)

internal fun pushHealth(enabled: Boolean?, connected: Boolean?): HealthReading = when {
    enabled == false -> HealthReading(HealthState.OFF, HealthReason.DISABLED)
    enabled == null || connected == null -> unknownHealth
    connected -> HealthReading(HealthState.CONNECTED, HealthReason.CONNECTED)
    else -> HealthReading(HealthState.ATTENTION, HealthReason.DISCONNECTED)
}

internal data class ContactHealthObservation(val enabled: Boolean, val scheduled: Boolean?, val status: HealthReason, val lastSuccessAt: Long)
internal fun contactsHealth(accounts: List<ContactHealthObservation>?, masterSync: Boolean?, permitted: Boolean? = true): HealthReading {
    if (accounts == null) return unknownHealth
    if (accounts.isEmpty()) return HealthReading(HealthState.OFF, HealthReason.NO_ACCOUNTS)
    val active = accounts.filter { it.enabled }
    if (active.isEmpty()) return HealthReading(HealthState.OFF, HealthReason.DISABLED)
    // Retain a recorded success when scheduling pauses or a later attempt fails.
    // One account's success must never hide another enabled account's first sync.
    val success = active.map { it.lastSuccessAt }.takeIf { times -> times.all { it > 0 } }?.minOrNull()
    if (masterSync == false || active.any { it.scheduled == false }) return HealthReading(HealthState.PAUSED, HealthReason.SYNC_PAUSED, success)
    if (masterSync == null || active.any { it.scheduled == null }) return unknownHealth
    if (permitted == false) return HealthReading(HealthState.ATTENTION, HealthReason.CONTACT_PERMISSION, success)
    if (permitted == null) return unknownHealth
    val errors = setOf(HealthReason.AUTHORIZATION, HealthReason.NETWORK, HealthReason.LOCAL, HealthReason.CONFLICT, HealthReason.UNCERTAIN)
    active.firstOrNull { it.status in errors }?.let { return HealthReading(HealthState.ATTENTION, it.status, success) }
    if (active.any { it.status == HealthReason.UNREADABLE }) return unknownHealth
    return HealthReading(HealthState.READY, if (success == null) HealthReason.SYNC_PENDING else HealthReason.SYNC_SUCCESS, success)
}

internal fun contactHealthReason(stored: String): HealthReason = when (stored) {
    "success" -> HealthReason.SYNC_SUCCESS
    "authorization" -> HealthReason.AUTHORIZATION
    "network" -> HealthReason.NETWORK
    "local" -> HealthReason.LOCAL
    "conflict" -> HealthReason.CONFLICT
    "uncertain" -> HealthReason.UNCERTAIN
    "" -> HealthReason.SYNC_PENDING
    else -> HealthReason.UNREADABLE
}

internal fun calendarHealth(accounts: List<ContactHealthObservation>?, masterSync: Boolean?, permitted: Boolean?): HealthReading =
    contactsHealth(accounts, masterSync, permitted).let {
        if (it.reason == HealthReason.CONTACT_PERMISSION) it.copy(reason = HealthReason.CALENDAR_PERMISSION) else it
    }

internal fun locationHealth(systemEnabled: Boolean?, configured: Boolean?, permitted: Boolean?): HealthReading = when {
    systemEnabled == false || configured == false -> HealthReading(HealthState.OFF, HealthReason.DISABLED)
    systemEnabled == null || configured == null || permitted == null -> unknownHealth
    !permitted -> HealthReading(HealthState.ATTENTION, HealthReason.LOCATION_PERMISSION)
    else -> HealthReading(HealthState.READY, HealthReason.LOCATION_READY)
}

internal fun registrationHealth(enabled: Boolean?, lastCheckin: Long?): HealthReading = when {
    enabled == false -> HealthReading(HealthState.OFF, HealthReason.DISABLED)
    enabled == null || lastCheckin == null -> unknownHealth
    lastCheckin > 0 -> HealthReading(HealthState.READY, HealthReason.REGISTRATION_RECORDED, lastCheckin)
    else -> HealthReading(HealthState.ATTENTION, HealthReason.REGISTRATION_PENDING)
}

internal data class ServiceHealthSnapshot(
    val push: HealthReading = unknownHealth,
    val contacts: HealthReading = unknownHealth,
    val location: HealthReading = unknownHealth,
    val registration: HealthReading = unknownHealth,
    val observedAt: Long = 0,
    val servicesVersionCode: Long,
    val companionVersionCode: Long? = null,
    val androidApi: Int,
    val calendar: HealthReading = unknownHealth
) {
    /** Plain text generated solely from enums and numbers. Never include exception text, endpoints or names. */
    fun diagnosticReport(): String = buildString {
        appendLine("Cyclon Services diagnostic report · format 2")
        appendLine("services_version_code=$servicesVersionCode")
        appendLine("companion_version_code=${companionVersionCode ?: "unknown"}")
        appendLine("android_api=$androidApi")
        appendLine("observed_at_ms=$observedAt")
        for ((key, reading) in listOf("push" to push, "contacts" to contacts, "calendar" to calendar, "network_location" to location, "registration" to registration)) {
            appendLine("$key=${reading.state.name.lowercase()}; code=${reading.reason.name.lowercase()}")
            reading.lastSuccessAt?.takeIf { it > 0 }?.let { appendLine("${key}_last_success_ms=$it") }
        }
        append("Settings and recorded observations only; no certification or end-to-end delivery claim.")
    }
}
