/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

internal enum class AppReason { UNREADABLE, APP_OFF, PUSH_OFF, APP_BLOCKED, DISCONNECTED,
    AUTHORIZATION, NETWORK, REGISTRATION_ERROR, REGISTERED, NO_REGISTRATION,
    NOTIFICATION_ALLOWED, NOTIFICATION_DENIED, LOCATION_READY, LOCATION_PERMISSION, LOCATION_OFF, NOT_TESTED }
internal enum class AppRecovery { APP_SETTINGS, PUSH_SETTINGS, PUSH_APP_SETTINGS, LOCATION_SETTINGS, ACCOUNTS, OPEN_APP }
internal data class AppReading(val reason: AppReason, val recovery: AppRecovery)
internal data class AppPushObservation(val registered: Boolean, val allowed: Boolean, val error: String?, val lastMessageAt: Long)
internal fun appPushReading(enabled: Boolean?, global: HealthReading, app: AppPushObservation?): AppReading = when {
    enabled == false -> AppReading(AppReason.APP_OFF, AppRecovery.APP_SETTINGS)
    enabled == null -> AppReading(AppReason.UNREADABLE, AppRecovery.APP_SETTINGS)
    global.state == HealthState.OFF -> AppReading(AppReason.PUSH_OFF, AppRecovery.PUSH_SETTINGS)
    app?.allowed == false -> AppReading(AppReason.APP_BLOCKED, AppRecovery.PUSH_APP_SETTINGS)
    global.state == HealthState.UNKNOWN || app == null -> AppReading(AppReason.UNREADABLE, AppRecovery.PUSH_SETTINGS)
    app.error in setOf("authentication_failed", "phone_registration_error") -> AppReading(AppReason.AUTHORIZATION, AppRecovery.PUSH_SETTINGS)
    app.error in setOf("service_not_available", "retry_later", "timeout") -> AppReading(AppReason.NETWORK, AppRecovery.PUSH_SETTINGS)
    app.error != null -> AppReading(AppReason.REGISTRATION_ERROR, AppRecovery.PUSH_APP_SETTINGS)
    !app.registered -> AppReading(AppReason.NO_REGISTRATION, AppRecovery.OPEN_APP)
    global.state != HealthState.CONNECTED -> AppReading(AppReason.DISCONNECTED, AppRecovery.PUSH_SETTINGS)
    else -> AppReading(AppReason.REGISTERED, AppRecovery.PUSH_APP_SETTINGS)
}
internal fun appLocationReading(global: HealthReading, permitted: Boolean?): AppReading = when {
    permitted == false -> AppReading(AppReason.LOCATION_PERMISSION, AppRecovery.APP_SETTINGS)
    global.state == HealthState.OFF -> AppReading(AppReason.LOCATION_OFF, AppRecovery.LOCATION_SETTINGS)
    permitted == null || global.state == HealthState.UNKNOWN -> AppReading(AppReason.UNREADABLE, AppRecovery.LOCATION_SETTINGS)
    global.state == HealthState.READY -> AppReading(AppReason.LOCATION_READY, AppRecovery.LOCATION_SETTINGS)
    else -> AppReading(AppReason.LOCATION_PERMISSION, AppRecovery.LOCATION_SETTINGS)
}
