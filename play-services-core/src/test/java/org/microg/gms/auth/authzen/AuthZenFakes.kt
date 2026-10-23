/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.json.JSONArray
import org.json.JSONObject
import securegcm.Type

internal class InMemoryStorage : AuthZenStorage {
    val files = HashMap<String, ByteArray>()
    override fun read(name: String) = files[name]?.copyOf()
    override fun write(name: String, bytes: ByteArray) {
        files[name] = bytes.copyOf()
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

internal object TestLock : AuthZenLock {
    override fun <T> withLock(name: String, block: () -> T): T = synchronized(this) { block() }
}

internal class TestClock(var wall: Long = 1_800_000_000_000L, var elapsed: Long = 50_000L, var boot: String = "1") {
    fun now() = AuthZenNow(wall, elapsed, boot)
    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }
}

/**
 * A SYNTHETIC profile for host tests only: its "wire format" is JSON invented here, not Google's. It lets the tests
 * drive the decoder, reducer, consent and send boundaries end to end without claiming any real schema.
 */
internal class SyntheticProfile(private val replyOutcome: (JSONObject) -> SendOutcome = { if (it.optBoolean("recorded")) SendOutcome.CONFIRMED else SendOutcome.UNKNOWN }) : AuthZenProfile {
    override val name = "synthetic-test"
    val replies = mutableListOf<Pair<AuthZenDecision, ByteArray>>()

    override fun accepts(type: Type, version: Int) = version == 1

    override fun decodeRequest(body: ByteArray): AuthZenRequest {
        val json = JSONObject(String(body))
        if (json.optBoolean("unsupportedTrigger")) throw AuthZenDecodeException(UnsupportedReason.UNSUPPORTED_CONTENT)
        val choices = json.optJSONArray("choices") ?: JSONArray()
        return AuthZenRequest(
            transactionId = json.getString("id").toByteArray(),
            createdAtMillis = json.getLong("created"),
            expiresAtMillis = json.getLong("expires"),
            numberChoices = (0 until choices.length()).map { choices.getInt(it) },
            correctNumber = if (json.has("correct")) json.getInt("correct") else null,
            display = json.optJSONArray("display")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
            originalBody = body,
        )
    }

    override fun decodeCancel(body: ByteArray): ByteArray = JSONObject(String(body)).getString("id").toByteArray()

    override fun encodeReply(request: AuthZenRequest, decision: AuthZenDecision, decidedAtMillis: Long): ByteArray =
        (request.originalBody + "|${decision.name}".toByteArray()).also { replies += decision to it }

    override fun replyEnvelope(account: String, signcryptedReply: ByteArray): JSONObject =
        JSONObject().put("ledger", signcryptedReply.size)

    override fun interpretReply(response: JSONObject) = replyOutcome(response)
}

internal fun syntheticRequestBody(
    id: String = "tx-1",
    created: Long = 1_800_000_000_000L,
    expires: Long = created + 5 * 60_000L,
    choices: List<Int> = listOf(32, 57, 81),
    correct: Int? = 32,
    display: List<String> = listOf("Chrome on Mac"),
): ByteArray = JSONObject().apply {
    put("id", id)
    put("created", created)
    put("expires", expires)
    if (choices.isNotEmpty()) put("choices", JSONArray(choices))
    correct?.let { put("correct", it) }
    put("display", JSONArray(display))
}.toString().toByteArray()
