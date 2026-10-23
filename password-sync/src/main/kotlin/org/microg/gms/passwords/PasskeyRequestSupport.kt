/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** One capability decision for native picker advertising and final redemption. Local FIDO capabilities are separate. */
object PasskeyRequestSupport {
    fun create(algorithms: Collection<Int>, attestation: String?, attachment: String?, extensions: Set<String>): Boolean =
        -7 in algorithms && attestation in setOf(null, "none") && attachment in setOf(null, "platform") &&
            extensions.all { it in setOf("credProps", "prf") }
    fun get(extensions: Set<String>): Boolean = extensions.all { it == "prf" }
}
