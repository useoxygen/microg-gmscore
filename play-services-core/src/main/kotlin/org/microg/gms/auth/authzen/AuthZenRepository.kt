/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable transaction store shared by every GmsCore process. Each call reads, reduces and writes under one
 * cross-process lock, so concurrent taps, duplicate pushes and a recreated Activity all see one ordered history.
 * Writes happen before effects: a send starts only after `SENDING` is stored.
 */
internal class AuthZenRepository(
    private val storage: AuthZenStorage,
    private val lock: AuthZenLock,
    private val reducer: AuthZenReducer = AuthZenReducer(),
) {
    fun apply(action: AuthZenAction, now: AuthZenNow): AuthZenResult? = lock.withLock(FILE) {
        val (records, result) = reducer.reduce(read(), action, now)
        storage.write(FILE, encode(records))
        result
    }

    /** A record after reconciling expiry, for rendering. Never trusts anything but the store. */
    fun find(handle: String, now: AuthZenNow): AuthZenRecord? {
        apply(AuthZenAction.Reconcile, now)
        return read().firstOrNull { it.handle == handle }
    }

    fun all(now: AuthZenNow): List<AuthZenRecord> {
        apply(AuthZenAction.Reconcile, now)
        return read()
    }

    /** An unreadable store fails closed: no request can be shown or answered from it. */
    private fun read(): List<AuthZenRecord> = storage.read(FILE)?.let {
        try {
            decode(it)
        } catch (e: Exception) {
            null
        }
    } ?: emptyList()

    companion object {
        private const val FILE = "transactions"
        private const val FORMAT = 1

        private fun ByteArray.b64() = toByteString().base64()
        private fun String.unb64() = decodeBase64()!!.toByteArray()
        private fun JSONObject.optStringOrNull(name: String) = if (has(name) && !isNull(name)) getString(name) else null
        private fun ints(array: JSONArray) = (0 until array.length()).map { array.getInt(it) }

        fun encode(records: List<AuthZenRecord>): ByteArray = JSONObject().put("format", FORMAT).put("records", JSONArray().apply {
            records.forEach { record ->
                put(JSONObject().apply {
                    put("handle", record.handle)
                    put("account", record.account)
                    put("generation", record.generation)
                    put("transactionId", record.transactionId)
                    record.digest?.let { put("digest", it) }
                    record.profile?.let { put("profile", it) }
                    put("state", record.state.name)
                    put("displayChoices", JSONArray(record.displayChoices))
                    put("bootId", record.bootId)
                    put("deadlineElapsed", record.deadlineElapsedMillis)
                    put("serverExpiresAt", record.serverExpiresAtMillis)
                    put("updatedWall", record.updatedWallMillis)
                    record.decision?.let { put("decision", it.name) }
                    record.authAttempt?.let { put("authAttempt", it) }
                    record.operation?.let { put("operation", it) }
                    record.request?.let { request ->
                        put("request", JSONObject().apply {
                            put("transactionId", request.transactionId.b64())
                            put("createdAt", request.createdAtMillis)
                            put("expiresAt", request.expiresAtMillis)
                            put("numberChoices", JSONArray(request.numberChoices))
                            request.correctNumber?.let { put("correctNumber", it) }
                            put("display", JSONArray(request.display))
                            put("originalBody", request.originalBody.b64())
                        })
                    }
                })
            }
        }).toString().toByteArray()

        fun decode(bytes: ByteArray): List<AuthZenRecord> {
            val json = JSONObject(String(bytes))
            require(json.getInt("format") == FORMAT)
            val array = json.getJSONArray("records")
            return (0 until array.length()).map { array.getJSONObject(it) }.map { record ->
                val request = record.optJSONObject("request")?.let {
                    val display = it.getJSONArray("display")
                    AuthZenRequest(
                        transactionId = it.getString("transactionId").unb64(),
                        createdAtMillis = it.getLong("createdAt"),
                        expiresAtMillis = it.getLong("expiresAt"),
                        numberChoices = ints(it.getJSONArray("numberChoices")),
                        correctNumber = if (it.has("correctNumber")) it.getInt("correctNumber") else null,
                        display = (0 until display.length()).map { index -> display.getString(index) },
                        originalBody = it.getString("originalBody").unb64(),
                    )
                }
                AuthZenRecord(
                    handle = record.getString("handle"),
                    account = record.getString("account"),
                    generation = record.getLong("generation"),
                    transactionId = record.getString("transactionId"),
                    digest = record.optStringOrNull("digest"),
                    profile = record.optStringOrNull("profile"),
                    state = AuthZenState.valueOf(record.getString("state")),
                    request = request,
                    displayChoices = ints(record.getJSONArray("displayChoices")),
                    bootId = record.getString("bootId"),
                    deadlineElapsedMillis = record.getLong("deadlineElapsed"),
                    serverExpiresAtMillis = record.getLong("serverExpiresAt"),
                    updatedWallMillis = record.getLong("updatedWall"),
                    decision = record.optStringOrNull("decision")?.let { AuthZenDecision.valueOf(it) },
                    authAttempt = record.optStringOrNull("authAttempt"),
                    operation = record.optStringOrNull("operation"),
                )
            }
        }
    }
}
