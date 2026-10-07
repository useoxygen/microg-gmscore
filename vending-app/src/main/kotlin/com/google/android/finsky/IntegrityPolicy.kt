/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.finsky

import com.google.android.finsky.model.IntegrityErrorCode
import com.google.android.finsky.model.StandardIntegrityException
import java.io.IOException

/** All timestamps and durations here are seconds, matching protobuf Timestamp.seconds. */
internal fun shouldRefreshDeviceIntegrity(
    nowSeconds: Long,
    createdSeconds: Long,
    lastRefreshSeconds: Long,
    hardExpirationSeconds: Long,
    softExpirationSeconds: Long,
    checkPeriodSeconds: Long,
    hasToken: Boolean,
): Boolean {
    if (!hasToken || createdSeconds <= 0 || createdSeconds > nowSeconds || lastRefreshSeconds > nowSeconds) return true
    val age = nowSeconds - createdSeconds
    return age >= hardExpirationSeconds ||
        (age >= softExpirationSeconds && nowSeconds - lastRefreshSeconds >= checkPeriodSeconds)
}

internal fun Throwable.classicIntegrityErrorCode(): Int = when (this) {
    is StandardIntegrityException -> code
    is IOException -> IntegrityErrorCode.NETWORK_ERROR
    else -> IntegrityErrorCode.INTERNAL_ERROR
}
