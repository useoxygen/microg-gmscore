/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.provider

/** The fix age FusedLocationProviderService forwards for Android's merged request (no Android types: unit-tested). */
internal object FusedRequestAge {
    // LocationProviderManager.MAX_CURRENT_LOCATION_AGE_MS
    private const val CURRENT_LOCATION_MAX_AGE_MILLIS = 30_000L

    /**
     * A one-off getCurrentLocation reaches the provider as interval 0 (LocationManager's LocationRequest.Builder(0));
     * Android itself answers such a request with a fix up to 30 s old, but only knows the fixes we reported, and the
     * implicit age limit (the interval) held back one we had just computed for another client until the next fix, often
     * past the caller's timeout. A continuous subscription (any other interval) keeps the default: a navigation request
     * must not start from a position up to 30 s behind. Null leaves the builder's default.
     */
    fun maxUpdateAgeMillis(interval: Long?): Long? = if (interval == 0L) CURRENT_LOCATION_MAX_AGE_MILLIS else null
}
