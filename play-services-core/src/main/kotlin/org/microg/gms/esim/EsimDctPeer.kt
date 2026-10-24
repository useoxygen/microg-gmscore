/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.content.Context
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.migration.controller.DctController
import org.microg.gms.migration.payload.DctHttpReply
import org.microg.gms.migration.payload.DctHttpRequest
import org.microg.gms.migration.session.DctSessions
import org.microg.gms.migration.session.DctSession
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** This route is reachable only through the authenticated, Network-bound DCT controller. */
internal object EsimDctRouter {
    const val TYPE = "application/vnd.os-migration.esim+proto"
    private val peers = java.util.concurrent.ConcurrentHashMap<DctController, EsimDctPeer>()
    private val pending = java.util.concurrent.ConcurrentHashMap<DctController, ArrayBlockingQueue<ByteArray>>()
    @Synchronized fun register(controller: DctController, peer: EsimDctPeer) {
        check(peers.putIfAbsent(controller, peer) == null)
        pending.remove(controller)?.forEach { peer.receive(it) }
    }
    @Synchronized fun closed(controller: DctController) { peers.remove(controller)?.close(); pending.remove(controller)?.forEach { it.fill(0) } }
    @Synchronized private fun receive(controller: DctController, bytes: ByteArray) {
        require(!controller.isClosed && bytes.size <= 1024 * 1024)
        val peer = peers[controller]
        if (peer != null) peer.receive(bytes)
        else if (!pending.computeIfAbsent(controller) { ArrayBlockingQueue(8) }.offer(bytes.copyOf())) throw EsimFailure(48572)
    }
    @Synchronized fun remove(controller: DctController, peer: EsimDctPeer) { peers.remove(controller, peer) }
    fun oneWay(controller: DctController, type: String, bytes: ByteArray): Boolean {
        if (type.substringBefore(';') != TYPE) return false
        receive(controller, bytes); return true
    }
    fun incoming(controller: DctController, request: DctHttpRequest): DctHttpReply? {
        if (request.path.substringBefore('?') != "/_callback/esim") return null
        require(request.method == "POST" && request.headers["content-type"]?.substringBefore(';') == TYPE)
        receive(controller, requireNotNull(request.bytes))
        return DctHttpReply(TYPE, byteArrayOf().toRequestBody())
    }
}

internal interface EsimPeer : Closeable {
    fun exchange(envelope: ByteArray, expectedField: Int): EsimProto
    fun notify(envelope: ByteArray)
}
internal class EsimDctPeer private constructor(private val controller: DctController, private val ownsController: Boolean) : EsimPeer {
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    @Volatile private var closed = false
    init { EsimDctRouter.register(controller, this) }
    fun receive(bytes: ByteArray) {
        require(!closed && bytes.size <= 1024 * 1024)
        if (!queue.offer(bytes.copyOf())) throw EsimFailure(48572)
    }
    @Synchronized override fun exchange(envelope: ByteArray, expectedField: Int): EsimProto {
        notify(envelope)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!closed && !controller.isClosed && System.nanoTime() < deadline) {
            val bytes = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            try {
                val message = EsimProto.decode(bytes)
                require(message.keys() == setOf(expectedField)) { "Unexpected eSIM envelope" }
                return requireNotNull(message.message(expectedField))
            } finally { bytes.fill(0) }
        }
        throw EsimFailure(48572)
    }
    override fun notify(envelope: ByteArray) {
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!closed && !controller.isClosed && controller.peer == null && System.nanoTime() < readyDeadline) Thread.sleep(50)
        if (closed || controller.isClosed || controller.peer == null) throw EsimFailure(48502)
        val client = controller.http
        if (client != null) {
            try { client.request("/_callback/esim", EsimDctRouter.TYPE, envelope).execute().use {
                if (!it.isSuccessful) throw EsimFailure(48572)
                val source = it.body?.source(); if (source != null && !source.exhausted()) {
                    require(!source.request(1024 * 1024L + 1)); receive(source.readByteArray())
                }
            } } catch (failure: EsimFailure) { throw failure } catch (_: Exception) { throw EsimFailure(48572) }
        } else if (controller.publisher?.oneWay(EsimDctRouter.TYPE, envelope) != true) throw EsimFailure(48572)
    }
    override fun close() { closed = true; EsimDctRouter.remove(controller, this); queue.forEach { it.fill(0) }; queue.clear(); if (ownsController) controller.close() }
    companion object {
        fun connect(context: Context, request: EsimWireRecord): EsimDctPeer {
            val key = request.text(7); val id = request.text(8)
            var owned = false
            val controller = DctSessions.retained() ?: run {
                if (key.isNullOrBlank() || id.isNullOrBlank()) throw EsimFailure(48502)
                val session = try { DctSession.restore(id, key) } catch (_: Exception) { throw EsimFailure(48508) }
                owned = true
                DctSessions.create(context, session, "com.google.android.gms.esim", supportedServices = if (request.int(2) == 3) 2 else 1)
            }
            if (!key.isNullOrEmpty() && key != controller.session.password || !id.isNullOrEmpty() && id != controller.session.sessionId) throw EsimFailure(48508)
            return EsimDctPeer(controller, owned).also { if (owned) controller.start() }
        }
    }
}
