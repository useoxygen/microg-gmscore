/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Adapts Chrome's distinct first-party request shapes to the internal vault interface. */
object ChromePasswordWire {
    /** bexa/bfgl.g consume bare specifics, unlike beyb's ChromePassword upsert wrapper. */
    fun deleteInput(entity: ByteArray): ByteArray {
        PasswordData.decode(entity)
        return Proto.field(1, entity)
    }
}
