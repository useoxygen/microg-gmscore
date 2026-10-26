/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

class PasswordWifiDeviceNetwork(val networkId: Int, val creatorUid: Int, val eligible: Boolean, val network: PasswordWifiNetwork) {
    override fun toString(): String = "PasswordWifiDeviceNetwork[redacted]"
}

/** Every method must recheck privileged admission and the selected account before touching WifiManager. */
interface PasswordWifiPlatform {
    val uid: Int
    fun snapshot(): List<PasswordWifiDeviceNetwork>
    fun add(network: PasswordWifiNetwork): Int
    fun update(before: PasswordWifiDeviceNetwork, network: PasswordWifiNetwork): Int
    fun remove(before: PasswordWifiDeviceNetwork): Boolean
}

private class WifiBaseline(val tag: String, val local: ByteArray?, val remote: ByteArray?, val ownedId: Int?) {
    fun encode(): ByteArray = Proto.field(1, tag) + (local?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        (remote?.let { Proto.field(3, it) } ?: byteArrayOf()) + (ownedId?.let { Proto.integer(4, it.toLong()) } ?: byteArrayOf())
    companion object {
        fun decode(raw: ByteArray): WifiBaseline = Proto.decode(raw).let {
            val baseline = WifiBaseline(it.string(1), it.optional(2), it.optional(3), it.integer(4)?.toInt())
            if (baseline.local?.let { network -> PasswordWifiNetwork.decode(network).tag() != baseline.tag } == true ||
                baseline.remote?.let { network -> PasswordWifiNetwork.decode(network).tag() != baseline.tag } == true ||
                baseline.ownedId?.let { id -> id < 0 } == true) throw ProtocolException()
            baseline
        }
    }
}

/** Entire object lives only inside the account-bound AndroidVaultStore AEAD snapshot. */
class PasswordWifiState private constructor(
    var enabled: Boolean = false,
    var parent: String? = null,
    private val baselines: MutableMap<String, WifiBaseline> = HashMap(),
    val cloudMutations: MutableList<Mutation> = ArrayList(),
    val deviceMutations: MutableList<Mutation> = ArrayList(),
    var lastSyncMillis: Long = 0,
    var conflicts: Int = 0
) {
    fun encode(): ByteArray = Proto.integer(1, 1) + Proto.integer(2, if (enabled) 1 else 0) +
        (parent?.let { Proto.field(3, it) } ?: byteArrayOf()) + baselines.values.fold(byteArrayOf()) { acc, b -> acc + Proto.field(4, b.encode()) } +
        cloudMutations.fold(byteArrayOf()) { acc, m -> acc + Proto.field(5, m.encode()) } +
        deviceMutations.fold(byteArrayOf()) { acc, m -> acc + Proto.field(6, m.encode()) } +
        Proto.integer(7, lastSyncMillis) + Proto.integer(8, conflicts.toLong())
    val pending: Int get() = cloudMutations.size + deviceMutations.size
    val indeterminate: Int get() = (cloudMutations + deviceMutations).count { it.phase in setOf(MutationPhase.LAUNCHED, MutationPhase.INDETERMINATE) }
    internal fun baseline(tag: String): Triple<ByteArray?, ByteArray?, Int?>? = baselines[tag]?.let { Triple(it.local, it.remote, it.ownedId) }
    internal fun remember(tag: String, local: PasswordWifiDeviceNetwork?, remote: PasswordWifiNetwork?, ownedId: Int?) {
        if (local == null && remote == null) baselines.remove(tag)
        else baselines[tag] = WifiBaseline(tag, local?.network?.encode(), remote?.encode(), ownedId)
    }
    internal fun tags(): Set<String> = baselines.keys.toSet()
    override fun toString(): String = "PasswordWifiState[redacted]"
    companion object {
        fun decode(raw: ByteArray?): PasswordWifiState {
            if (raw == null) return PasswordWifiState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.integer(2) !in setOf(0L, 1L)) throw ProtocolException()
            val baselines = p.bytes(4).map(WifiBaseline::decode)
            val cloud = p.bytes(5).map(Mutation::decode)
            val device = p.bytes(6).map(Mutation::decode)
            if (baselines.size > 10_000 || cloud.size + device.size > 10_000 || baselines.map { it.tag }.toSet().size != baselines.size ||
                cloud.map { it.tag }.toSet().size != cloud.size || device.map { it.tag }.toSet().size != device.size ||
                (cloud + device).map { it.id }.toSet().size != cloud.size + device.size) throw ProtocolException()
            return PasswordWifiState(p.integer(2) == 1L, p.optionalString(3)?.let(PasswordApi::checkedParent),
                baselines.associateBy { it.tag }.toMutableMap(), cloud.toMutableList(), device.toMutableList(),
                p.integer(7) ?: 0, (p.integer(8) ?: 0).toInt())
        }
    }
}

/** Three-way merge with durable launch fences for both cloud writes and the native consumer. */
class PasswordWifiMerge(private val api: PasswordWifiApi, private val platform: PasswordWifiPlatform,
    private val verifyAccess: () -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    fun synchronize(session: VaultSession, state: VaultState, parent: String, keys: VaultKeys, token: ByteArray = byteArrayOf(),
        ownerAuthorizesRetry: Boolean = false): PasswordWifiState {
        val wifi = PasswordWifiState.decode(state.wifiSync)
        if (!wifi.enabled) return wifi // No native credential access or network before explicit opt-in.
        PasswordApi.checkedParent(parent)
        if (wifi.parent != null && wifi.parent != parent || state.pendingMetadata != null) throw VaultResetException()
        wifi.parent = parent
        fun save() { verifyAccess(); state.wifiSync = wifi.encode(); session.save(state) }
        var cloud = api.snapshot(parent).associateBy { Proto.decode(it).string(2) }.toMutableMap()
        // Authenticate every record before interpreting absence or making a local mutation.
        cloud.values.forEach { PasswordWifiResource.read(it, keys) }
        var local = snapshot()
        save()
        val claimed = HashMap<String, Int>()
        var foundConflicts = 0

        fun cloudWrites() {
            for (old in wifi.cloudMutations.toList()) {
                val remote = cloud[old.tag]
                val desired = old.desired?.let { PasswordWifiResource.read(it, keys) }
                val applied = if (old.kind == MutationKind.DELETE) remote == null else remote != null &&
                    PasswordWifiResource.read(remote, keys).sameConfiguration(desired ?: throw ProtocolException())
                if (applied) { wifi.cloudMutations.removeAll { it.id == old.id }; save(); continue }
                local = snapshot()
                val source = local[old.tag]
                if (if (old.kind == MutationKind.DELETE) source != null else source == null || !source.eligible || !source.network.sameConfiguration(desired!!)) {
                    replace(wifi.cloudMutations, old.phase(MutationPhase.CONFLICT)); save(); continue
                }
                var pending = old
                val compatible = if (pending.kind == MutationKind.CREATE) remote == null else remote != null &&
                    Proto.decode(remote).string(1) == pending.name && equal(Proto.decode(remote).optional(5), pending.baseVersion)
                if (pending.phase != MutationPhase.QUEUED) {
                    if (!ownerAuthorizesRetry || !compatible) {
                        replace(wifi.cloudMutations, pending.phase(if (pending.phase == MutationPhase.CONFLICT) MutationPhase.CONFLICT else MutationPhase.INDETERMINATE))
                        save(); continue
                    }
                    pending = pending.phase(MutationPhase.QUEUED)
                }
                if (!compatible) { replace(wifi.cloudMutations, pending.phase(MutationPhase.CONFLICT)); save(); continue }
                val outgoing = desired?.let { PasswordWifiResource.write(it, keys, pending.desired) }
                replace(wifi.cloudMutations, pending.phase(MutationPhase.LAUNCHED)); save()
                try {
                    verifyAccess()
                    val result = when (pending.kind) {
                        MutationKind.CREATE -> api.create(parent, outgoing!!, token)
                        MutationKind.UPDATE -> api.update(outgoing!!, token)
                        MutationKind.DELETE -> { api.delete(remote!!, token); null }
                    }
                    verifyAccess()
                    if (result != null) {
                        PasswordWifiApi.checkedId(Proto.decode(result).string(1), parent)
                        if (!PasswordWifiResource.read(result, keys).sameConfiguration(desired!!)) throw ProtocolException()
                    }
                    if (result == null) cloud.remove(pending.tag) else cloud[pending.tag] = result
                    wifi.cloudMutations.removeAll { it.id == pending.id }; save()
                } catch (failure: Exception) {
                    replace(wifi.cloudMutations, pending.phase(if (failure is PasswordRpcException && !failure.outcomeUnknown)
                        if (failure.code in setOf(6, 10)) MutationPhase.CONFLICT else MutationPhase.QUEUED else MutationPhase.INDETERMINATE))
                    save(); throw failure
                }
            }
        }
        fun deviceWrites() {
            for (old in wifi.deviceMutations.toList()) {
                // Native identity, ownership and exact previous bytes are checked anew for each call.
                local = snapshot()
                val current = local[old.tag]
                val desired = old.desired?.let(PasswordWifiNetwork::decode)
                val applied = if (old.kind == MutationKind.DELETE) current == null else current != null &&
                    current.creatorUid == platform.uid && current.network.sameConfiguration(desired ?: throw ProtocolException()) &&
                    (old.name == null || current.networkId.toString() == old.name)
                if (applied) {
                    // The app UID is shared by every Google account. Existence after a queued
                    // or lost add proves the desired state, but not which account created it.
                    val ownedId = current?.networkId?.takeIf { old.name == it.toString() }
                    if (ownedId != null) claimed[old.tag] = ownedId
                    wifi.remember(old.tag, current, desired, ownedId)
                    wifi.deviceMutations.removeAll { it.id == old.id }; save(); continue
                }
                val source = cloud[old.tag]?.let { PasswordWifiResource.read(it, keys) }
                if (!same(source, desired)) { replace(wifi.deviceMutations, old.phase(MutationPhase.CONFLICT)); save(); continue }
                val compatible = if (old.kind == MutationKind.CREATE) current == null else current != null && current.eligible &&
                    current.creatorUid == platform.uid && current.networkId.toString() == old.name && equal(current.network.digest(), old.baseVersion)
                if (old.phase != MutationPhase.QUEUED && (!ownerAuthorizesRetry || !compatible)) {
                    replace(wifi.deviceMutations, old.phase(if (old.phase == MutationPhase.CONFLICT) MutationPhase.CONFLICT else MutationPhase.INDETERMINATE))
                    save(); continue
                }
                if (!compatible) { replace(wifi.deviceMutations, old.phase(MutationPhase.CONFLICT)); save(); continue }
                var launched = old.phase(MutationPhase.LAUNCHED)
                replace(wifi.deviceMutations, launched); save()
                try {
                    verifyAccess()
                    when (old.kind) {
                        MutationKind.CREATE -> {
                            val id = platform.add(desired!!)
                            if (id < 0) throw WifiMutationRefusedException()
                            // Persist the returned native identity before readback can fail.
                            // Only this receipt can authorize this account to manage the network.
                            launched = Mutation(old.id, old.kind, old.tag, id.toString(), old.baseVersion, old.desired, MutationPhase.LAUNCHED)
                            replace(wifi.deviceMutations, launched); save()
                        }
                        MutationKind.UPDATE -> {
                            val id = platform.update(current!!, desired!!)
                            if (id < 0) throw WifiMutationRefusedException()
                            if (id.toString() != old.name) throw ProtocolException()
                        }
                        MutationKind.DELETE -> if (!platform.remove(current!!)) throw WifiMutationRefusedException()
                    }
                    verifyAccess()
                    local = snapshot()
                    val result = local[old.tag]
                    if (old.kind == MutationKind.DELETE) { if (result != null) throw ProtocolException() }
                    else {
                        if (result == null || result.creatorUid != platform.uid || result.networkId.toString() != launched.name ||
                            !result.network.sameConfiguration(desired!!)) throw ProtocolException()
                        claimed[old.tag] = result.networkId
                    }
                    wifi.remember(old.tag, result, desired, result?.networkId)
                    wifi.deviceMutations.removeAll { it.id == old.id }; save()
                } catch (failure: Exception) {
                    // A Binder/transport exception can follow a successful WifiManager mutation.
                    replace(wifi.deviceMutations, launched.phase(if (failure is WifiMutationRefusedException) MutationPhase.QUEUED else MutationPhase.INDETERMINATE))
                    save(); throw failure
                }
            }
        }
        cloudWrites(); deviceWrites()
        val tags = wifi.tags() + cloud.keys + local.keys
        for (tag in tags) {
            if ((wifi.cloudMutations + wifi.deviceMutations).any { it.tag == tag }) continue
            val device = local[tag]
            val remoteRaw = cloud[tag]
            val remote = remoteRaw?.let { PasswordWifiResource.read(it, keys) }
            val previous = wifi.baseline(tag)
            val oldLocal = previous?.first?.let(PasswordWifiNetwork::decode)
            val oldRemote = previous?.second?.let(PasswordWifiNetwork::decode)
            val owned = device != null && device.creatorUid == platform.uid && previous?.third == device.networkId
            if (device != null && !device.eligible) { foundConflicts++; continue }
            if (same(device?.network, remote)) continue
            val localChanged = !same(device?.network, oldLocal)
            val cloudChanged = !same(remote, oldRemote)
            fun toCloud() {
                val desired = device?.network?.let { PasswordWifiResource.write(remote?.withConfiguration(it) ?: it, keys, remoteRaw) }
                wifi.cloudMutations.add(Mutation(UUID.randomUUID().toString(), when { device == null -> MutationKind.DELETE; remote == null -> MutationKind.CREATE; else -> MutationKind.UPDATE },
                    tag, remoteRaw?.let { Proto.decode(it).string(1) }, remoteRaw?.let { Proto.decode(it).optional(5) }, desired))
            }
            fun toDevice() {
                if (device != null && !owned) { foundConflicts++; return }
                wifi.deviceMutations.add(Mutation(UUID.randomUUID().toString(), when { remote == null -> MutationKind.DELETE; device == null -> MutationKind.CREATE; else -> MutationKind.UPDATE },
                    tag, device?.networkId?.toString(), device?.network?.digest(), remote?.encode()))
            }
            when {
                previous == null && remote == null -> toCloud()
                previous == null && device == null -> toDevice()
                previous == null -> foundConflicts++ // Never overwrite a preexisting network during initial merge.
                localChanged && !cloudChanged -> toCloud()
                cloudChanged && !localChanged -> toDevice()
                else -> foundConflicts++ // Concurrent differing edits require owner reconciliation.
            }
        }
        save() // Persist the complete planned batch before launching any effect.
        cloudWrites(); deviceWrites()
        local = snapshot()
        for (tag in tags + cloud.keys + local.keys) {
            if ((wifi.cloudMutations + wifi.deviceMutations).any { it.tag == tag }) continue
            val device = local[tag]
            val remote = cloud[tag]?.let { PasswordWifiResource.read(it, keys) }
            if (same(device?.network, remote)) {
                val previousId = wifi.baseline(tag)?.third
                val ownedId = claimed[tag] ?: previousId?.takeIf { device?.creatorUid == platform.uid && device.networkId == it }
                wifi.remember(tag, device, remote, ownedId)
            }
        }
        wifi.conflicts = foundConflicts + (wifi.cloudMutations + wifi.deviceMutations).count { it.phase == MutationPhase.CONFLICT }
        wifi.lastSyncMillis = now(); save()
        return wifi
    }
    private fun snapshot(): MutableMap<String, PasswordWifiDeviceNetwork> {
        verifyAccess()
        val records = platform.snapshot()
        verifyAccess()
        if (records.size > 10_000 || records.map { it.network.tag() }.toSet().size != records.size || records.map { it.networkId }.toSet().size != records.size) throw ProtocolException()
        return records.associateBy { it.network.tag() }.toMutableMap()
    }
    private fun same(a: PasswordWifiNetwork?, b: PasswordWifiNetwork?): Boolean = if (a == null || b == null) a == null && b == null else a.sameConfiguration(b)
    private fun equal(a: ByteArray?, b: ByteArray?): Boolean = a != null && b != null && MessageDigest.isEqual(a, b)
    private fun replace(list: MutableList<Mutation>, operation: Mutation) { val i = list.indexOfFirst { it.id == operation.id }; if (i < 0) throw ProtocolException(); list[i] = operation }
}
private class WifiMutationRefusedException : Exception("Wi-Fi mutation refused")
