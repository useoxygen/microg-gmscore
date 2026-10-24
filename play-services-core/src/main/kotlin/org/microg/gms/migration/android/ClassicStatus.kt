/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

/** Pinned GMS26.41.31 ConnectionsStatusCodes (eajo), also public SDK constants. */
internal object ClassicStatus {
    const val ALREADY_ADVERTISING=8001
    const val ALREADY_DISCOVERING=8002
    const val ALREADY_CONNECTED=8003
    const val NOT_CONNECTED=8005
    const val RADIO_ERROR=8007
    const val ACTIVE_STRATEGY=8008
    const val OUT_OF_ORDER=8009
    const val PAYLOAD_TYPE=8010
    const val ENDPOINT_UNKNOWN=8011
    const val ENDPOINT_IO=8012
    const val PAYLOAD_UNKNOWN=8014
    const val CAPABILITY=8017
}
