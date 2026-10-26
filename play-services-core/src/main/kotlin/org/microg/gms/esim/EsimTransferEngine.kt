/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.content.Context
import android.os.Parcel
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import java.io.Closeable
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/** GMS333 target session. Peer acknowledgement, carrier credential and LPA installation are separate. */
internal class EsimTransferEngine(private val context: Context, private val ownerUid: Int,
    private val entitlement: EsimEntitlement = EsimEntitlement({ EsimEntitlement.identity(context) }),
    private val peerFactory: (EsimWireRecord) -> EsimPeer = { EsimDctPeer.connect(context, it) },
    private val carrierId: (EsimProto) -> Int = { item -> resolveCarrier(context, item) }) : Closeable {
    @Volatile private var peer: EsimPeer? = null
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var session = 0L
    private val profiles = mutableListOf<EsimWireRecord>()
    private val carriers = mutableListOf<EsimWireRecord>()
    private val journal = context.getSharedPreferences("esim-transfer-receipts", Context.MODE_PRIVATE)
    private val outcomes = linkedMapOf<String, Int>()
    @Synchronized fun start(request: EsimWireRecord) {
        if (request.int(1) == 2) throw EsimFailure(48522)
        if (request.int(1) != 1) throw EsimFailure(48501)
        if (peer != null) throw EsimFailure(48502)
        cancelled.set(false)
        carriers.forEach { it.close() }; carriers.clear(); carriers += request.list(6)
        val connected = peerFactory(request)
        peer = connected
        try {
            val id = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
            val query = EsimProto.text(1, "1") + EsimProto.text(1, "2") + EsimProto.number(2, id) + EsimProto.text(3, android.os.Build.MODEL)
            val response = connected.exchange(EsimProto.envelope(1, query), 2)
            if (response.number(1) != 0L || response.number(2) != id) throw EsimFailure(48508)
            if (response.text(3) !in setOf("1", "2")) throw EsimFailure(48508)
            val incoming = response.messages(5)
            require(incoming.size <= 32)
            profiles.forEach { it.close() }; profiles.clear()
            incoming.forEach { profiles += convert(response, it) }
            if (cancelled.get()) throw EsimFailure(16)
            session = id; peer = connected
        } catch (e: Exception) { connected.close(); peer = null; throw e }
    }
    @Synchronized fun retrieveProfiles(): EsimWireRecord {
        if (peer == null || session == 0L) throw EsimFailure(48502)
        return EsimWireRecord.build { parcel ->
            Writer.write(parcel, 1, System.currentTimeMillis()); Writer.write(parcel, 2, profiles, 0, false)
        }
    }
    private fun receipt(profile: EsimProfile): String {
        // Session UUID changes after restart; it cannot identify a carrier mutation.
        val digest = MessageDigest.getInstance("SHA-256").digest(("$ownerUid:${profile.carrierId}:${profile.iccid}:${profile.entitlementUrl}").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun credential(record: EsimWireRecord): EsimWireRecord {
        if (cancelled.get()) throw EsimFailure(16)
        val connection = peer ?: throw EsimFailure(48502)
        EsimProfile(record).use { requested ->
            val cached = profiles.singleOrNull { it.text(4) == requested.id } ?: throw EsimFailure(48502)
            if (cached.long(5) != session) throw EsimFailure(48508)
            val knownIccid = cached.obj(2)?.use { it.text(1) }
            if (knownIccid != requested.iccid) throw EsimFailure(48508)
            // Use the session's authenticated carrier metadata, never a caller-supplied endpoint/token.
            val request = EsimProto.number(1, session) + EsimProto.text(2, requested.iccid)
            val response = connection.exchange(EsimProto.envelope(3, request), 4)
            if (response.number(1) != 0L || response.number(2) != session) throw EsimFailure(48507)
            val item = response.message(3) ?: throw EsimFailure(48507)
            if (item.text(1) != requested.iccid) throw EsimFailure(48508)
            val token = item.message(10) ?: throw EsimFailure(48507)
            val expiry = try { OffsetDateTime.parse(token.text(2)).toInstant().toEpochMilli() } catch (_: Exception) { throw EsimFailure(48552) }
            if (token.text(1).isBlank() || expiry <= System.currentTimeMillis()) throw EsimFailure(48552)
            val metadata = requireNotNull(cached.obj(3))
            val updatedMeta = metadata.use { it.replace(mapOf(
                8 to { out -> Writer.write(out, 8, token.text(1), false) },
                9 to { out -> Writer.write(out, 9, expiry) })) }
            val updated = updatedMeta.use { cached.replace(mapOf(3 to { out -> Writer.write(out, 3, it, 0, false) })) }
            EsimProfile(updated).use { profile ->
                if (profile.metadata.int(3) != 0) throw EsimFailure(48507)
                if (profile.transferType !in setOf(2, 7, 11)) throw EsimFailure(17)
                val key = receipt(profile)
                // An unknown carrier mutation is reconciled with AcquireConfiguration, never replayed.
                val reconcile = journal.contains(key)
                var dispatched = false
                try {
                    val result = entitlement.retrieve(profile, reconcile) {
                        check(journal.edit().putString(key, "dispatched").commit()); dispatched = true
                    }
                    if (cancelled.get()) { result.close(); throw EsimFailure(16) }
                    val index = profiles.indexOf(cached)
                    val retained = profile.wire.replace(emptyMap())
                    profiles[index] = retained; cached.close()
                    check(journal.edit().putString(key, "credential_received").commit())
                    outcomes[profile.iccid] = if (result.bool(4)) 4 else if (!result.text(1).isNullOrBlank()) 5 else 12
                    return result
                } catch (e: Exception) {
                    // Even a rejected/unsupported response can follow a carrier-side mutation. Never replay it.
                    if (dispatched || reconcile) check(journal.edit().putString(key, "unknown").commit())
                    outcomes[profile.iccid] = 7; throw e
                }
            }
        }
    }
    // Active subscriptions are read with the privileged READ_PRIVILEGED_PHONE_STATE grant, which lint does not model.
    @android.annotation.SuppressLint("MissingPermission")
    @Synchronized fun activate(subId: Int, record: EsimWireRecord) {
        if (cancelled.get()) throw EsimFailure(16)
        if (peer == null) throw EsimFailure(48502)
        val id = record.text(4)
        val cached = profiles.singleOrNull { it.text(4) == id } ?: throw EsimFailure(48508)
        EsimProfile(cached.replace(emptyMap())).use { profile ->
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            val target = subscriptions.singleOrNull { it.subscriptionId == subId && it.isEmbedded } ?: throw EsimFailure(48535)
            val key = receipt(profile) + ":activation"
            if (journal.getString(key, null) in setOf("dispatched", "unknown", "activated")) throw EsimFailure(48572)
            var dispatched = false
            try {
                entitlement.activate(profile, target.iccId) {
                    check(journal.edit().putString(key, "dispatched").commit()); dispatched = true
                }
                check(journal.edit().putString(key, "activated").commit())
            } catch (e: Exception) {
                if (dispatched) check(journal.edit().putString(key, "unknown").commit()); throw e
            }
        }
    }
    @Synchronized fun notifyDownload(record: EsimWireRecord, status: Int) {
        val connection = peer ?: throw EsimFailure(48502)
        EsimProfile(record).use { profile ->
            val cached = profiles.singleOrNull { it.text(4) == profile.id } ?: throw EsimFailure(48508)
            if (cached.long(5) != session || cached.obj(2)?.use { it.text(1) } != profile.iccid)
                throw EsimFailure(48508)
            // Pinned cdfj.e maps the LPA API status to the XOS status; they are different enums.
            val xos = downloadStatus(status)
            val outcome = EsimProto.text(1, profile.iccid) + EsimProto.number(2, xos.toLong())
            connection.notify(EsimProto.envelope(5, EsimProto.number(1, session) + EsimProto.bytes(2, outcome)))
            outcomes[profile.iccid] = xos
        }
    }
    @Synchronized fun end() {
        val connection = peer
        try {
            if (connection != null) {
                val results = outcomes.map { (iccid, value) ->
                    val finished = when(value) { 3, 12 -> 7; 5 -> 8; else -> value }
                    EsimProto.bytes(2, EsimProto.text(1, iccid) + EsimProto.number(2, finished.toLong()))
                }.fold(byteArrayOf()) { a, b -> a + b }
                connection.notify(EsimProto.envelope(6, EsimProto.number(1, session) + results))
            }
        } finally { close() }
    }
    fun cancel() { cancelled.set(true); peer?.close() }
    @Synchronized override fun close() {
        cancelled.set(true)
        peer?.close(); peer = null; session = 0
        profiles.forEach { it.close() }; profiles.clear(); carriers.forEach { it.close() }; carriers.clear(); outcomes.clear()
    }
    private fun convert(response: EsimProto, item: EsimProto): EsimWireRecord {
        val iccid = item.text(1); require(iccid.matches(Regex("[0-9]{10,32}")))
        val id = carrierId(item); val carrier = carriers.singleOrNull { it.int(1) == id }
        val type = if (item.number(12) == 1L) 1 else 2
        val device = EsimWireRecord.build { Writer.write(it, 1, response.text(4), false); Writer.write(it, 2, item.text(9), false) }
        val profile = EsimWireRecord.build {
            Writer.write(it, 1, iccid, false); Writer.write(it, 3, type); Writer.write(it, 4, if (item.number(11) == 3L) 1 else 2)
            Writer.write(it, 6, item.text(7), false); Writer.write(it, 7, id); Writer.write(it, 8, item.text(6), false)
        }
        val metadata = EsimWireRecord.build {
            Writer.write(it, 1, carrier?.int(if (type == 1) 4 else 5, 2) ?: 2)
            Writer.write(it, 3, if (carrier?.bool(28) == true) 0 else 4)
            Writer.write(it, 5, carrier?.text(8), true); Writer.write(it, 10, carrier?.text(6), true)
            Writer.write(it, 11, carrier?.text(10), true); Writer.write(it, 12, carrier?.int(14) ?: 0)
            Writer.write(it, 17, carrier?.text(22), true); Writer.write(it, 19, carrier, 0, true)
        }
        return try { EsimWireRecord.build {
            Writer.write(it, 1, device, 0, false); Writer.write(it, 2, profile, 0, false); Writer.write(it, 3, metadata, 0, false)
            Writer.write(it, 4, UUID.randomUUID().toString(), false); Writer.write(it, 5, response.number(2))
        } } finally { device.close(); profile.close(); metadata.close() }
    }
    companion object {
        internal fun downloadStatus(status: Int) = when (status) { 0 -> 1; 1 -> 5; 2 -> 6; 3 -> 8; 4 -> 11; 5 -> 13; 6 -> 12; 7 -> 9; else -> 0 }
        private fun resolveCarrier(context: Context, item: EsimProto): Int = runCatching {
            val clazz = Class.forName("android.service.carrier.CarrierIdentifier")
            val identifier = clazz.getConstructor(*Array(6) { String::class.java }).newInstance(item.text(2), item.text(3),
                item.text(13).takeIf { it.isNotBlank() }, null, item.text(4), item.text(5))
            TelephonyManager::class.java.getMethod("getCarrierIdFromCarrierIdentifier", clazz)
                .invoke(context.getSystemService(TelephonyManager::class.java), identifier) as Int
        }.getOrDefault(-1)
    }
}
