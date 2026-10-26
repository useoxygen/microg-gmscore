/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

/** Credential IDs are scoped to their RP. Another RP's account metadata cannot exclude registration here. */
object PasskeyExclusions {
    fun contains(rpId: String, excluded: Collection<ByteArray>, credentials: Collection<SyncedPasskey>): Boolean {
        SyncedPasskey.checkedRp(rpId)
        return credentials.any { credential -> credential.rpId == rpId && excluded.any { id -> MessageDigest.isEqual(id, credential.credentialId) } }
    }
}
