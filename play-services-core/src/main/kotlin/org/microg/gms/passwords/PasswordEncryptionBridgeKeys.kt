/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONObject

/** Current Google datarelay B9/k9 output: exact domain map with epoch + Uint8Array JSON objects. */
internal object PasswordEncryptionBridgeKeys {
    fun parse(raw: String): List<VersionedTrustedVaultKey> {
        if (raw.length > 512 * 1024) throw ProtocolException()
        val json = JSONObject(raw)
        if (json.length() != 1 || !json.has("chromesync")) throw ProtocolException()
        val list = json.getJSONArray("chromesync")
        if (list.length() !in 1..100) throw ProtocolException()
        val result = ArrayList<VersionedTrustedVaultKey>(); val epochs = HashSet<Int>()
        try {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val number = item.get("epoch")
                if (number !is Number || number.toDouble() != number.toLong().toDouble()) throw ProtocolException()
                val epoch = number.toLong()
                if (epoch !in 0..Int.MAX_VALUE.toLong() || !epochs.add(epoch.toInt())) throw ProtocolException()
                val data = item.getJSONObject("key")
                if (data.length() !in 16..4096) throw ProtocolException()
                val material = ByteArray(data.length())
                try {
                    for (n in material.indices) {
                        val value = data.get(n.toString())
                        if (value !is Number || value.toDouble() != value.toInt().toDouble() || value.toInt() !in 0..255) throw ProtocolException()
                        material[n] = value.toInt().toByte()
                    }
                    // This source enrollment sends a previous zero 16-byte bootstrap key.
                    // Epoch zero remains unlock-only and is never promoted to version authority.
                    if (epoch == 0L) { if (material.size != 16 || material.any { it != 0.toByte() }) throw ProtocolException() }
                    else result.add(VersionedTrustedVaultKey(epoch.toInt(), material))
                } finally { material.fill(0) }
            }
            if (result.isEmpty()) throw ProtocolException()
            return result
        } catch (failure: Throwable) { result.forEach(VersionedTrustedVaultKey::destroy); throw failure }
    }
}
