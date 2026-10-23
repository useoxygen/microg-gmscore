/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import java.security.SecureRandom

/** Chrome's null account denotes a separate, local-only vault; it never selects a Google account. */
class DevicePasswordVault private constructor(private val store: AndroidVaultStore) : PasswordVault {
    constructor(context: Context) : this(AndroidVaultStore(context, "local-passwords-v1"))
    internal constructor(context: Context, fixtureIdentity: String) : this(AndroidVaultStore(context, fixtureIdentity))
    override val enabled = true
    override fun setEnabled(value: Boolean) { if (!value) throw UnsupportedOperationException() }
    override fun refresh() = SyncReceipt(list().size, 0, 0, 0)
    override fun initializeEmptyVault() = store.locked { session ->
        val state = session.load()
        initialize(state)
        session.save(state)
    }
    override fun list(ensureInitialSync: Boolean): List<PasswordView> = store.locked { session ->
        val state = session.load()
        val metadata = state.metadata ?: return@locked emptyList()
        val keys = VaultKeys.unlock(metadata, customKey = state.customKey ?: throw VaultLockedException())
        try { state.records.map { raw ->
            val entity = Proto.decode(raw)
            val tag = entity.string(2)
            val data = PasswordResourceCodec.read(raw, keys)
            PasswordView(tag, data, Proto.field(1, data.encode()) +
                (state.chromeLocalData[tag]?.let { Proto.field(3, it) } ?: byteArrayOf()))
        } } finally { keys.destroy() }
    }
    override fun upsert(chromePassword: ByteArray) = upsert(chromePassword, false)
    override fun upsertAutomatic(chromePassword: ByteArray) = upsert(chromePassword, true)
    private fun upsert(chromePassword: ByteArray, automatic: Boolean) = store.locked { session ->
        val wrapper = Proto.decode(chromePassword)
        val data = PasswordData.decode(wrapper.one(1))
        val tag = data.tag()
        val state = session.load()
        initialize(state)
        val keys = VaultKeys.unlock(state.metadata!!, customKey = state.customKey)
        try {
            if (automatic) for (raw in state.records) {
                val existing = PasswordResourceCodec.read(raw, keys)
                if (existing.blocked && existing.realm == data.realm) throw PasswordSaveBlockedException()
            }
            val old = state.records.singleOrNull { Proto.decode(it).string(2) == tag }
            val merged = old?.let { PasswordResourceCodec.read(it, keys).mergeFrom(data) } ?: data
            val entity = PasswordResourceCodec.encrypt(merged, keys, old ?: (Proto.field(1, "local/$tag") + Proto.field(2, tag)))
            state.records.removeAll { Proto.decode(it).string(2) == tag }; state.records.add(entity)
            wrapper.optional(3)?.let { state.chromeLocalData[tag] = it }
            session.save(state)
        } finally { keys.destroy() }
    }
    override fun delete(chromePassword: ByteArray) = store.locked { session ->
        val tag = PasswordData.decode(Proto.decode(chromePassword).one(1)).tag()
        val state = session.load()
        state.records.removeAll { Proto.decode(it).string(2) == tag }; state.chromeLocalData.remove(tag)
        session.save(state)
    }
    override fun setCustomKey(encryption: ByteArray, mac: ByteArray) { throw UnsupportedOperationException() }
    override fun unlockPassphrase(password: ByteArray) { throw UnsupportedOperationException() }
    override fun getPreference(name: String): String? = store.locked { session ->
        session.load().preferences.map(PriorityPreference::decode).singleOrNull { it.name == name }?.value
    }
    override fun setPreference(name: String, value: String) = store.locked { session ->
        val state = session.load()
        val preference = state.preferences.map(PriorityPreference::decode).singleOrNull { it.name == name }
            ?.withValue(value) ?: PriorityPreference.create(name, value)
        state.preferences.removeAll { PriorityPreference.decode(it).name == name }
        state.preferences.add(preference.encode())
        session.save(state)
    }
    private fun initialize(state: VaultState) {
        if (state.metadata != null) return
        val random = SecureRandom()
        val encryption = ByteArray(16).also(random::nextBytes)
        val mac = ByteArray(16).also(random::nextBytes)
        val key = NigoriKey(encryption, mac)
        encryption.fill(0); mac.fill(0)
        try {
            state.customKey = key.export()
            state.metadata = Proto.field(1, "local-device-vault") + Proto.field(3,
                Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 4))
        } finally { key.destroy() }
    }
}
