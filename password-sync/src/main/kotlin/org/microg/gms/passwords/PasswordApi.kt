/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

enum class PasswordMethod(val rpc: String, val mutates: Boolean,
    val service: String = "google.internal.chrome.sync.passwords.v1.Passwords",
    val host: String = "chromesyncpasswords-pa.googleapis.com") {
    GET_METADATA("GetMetadata", false), CREATE_METADATA("CreateMetadata", true),
    LIST("ListPasswords", false), GET("GetPassword", false),
    CREATE("CreatePassword", true), UPDATE("UpdatePassword", true),
    BATCH_UPDATE("BatchUpdatePasswords", true), DELETE("DeletePassword", true),
    LIST_PREFERENCES("ListPriorityPreferences", false), CREATE_PREFERENCE("CreatePriorityPreference", true),
    UPDATE_PREFERENCE("UpdatePriorityPreference", true),
    CREATE_DEVICE("CreateDeviceInfo", true, "google.internal.chrome.sync.entities.v1.SyncEntities", "chromesyncentities-pa.googleapis.com"),
    UPDATE_DEVICE("UpdateDeviceInfo", true, "google.internal.chrome.sync.entities.v1.SyncEntities", "chromesyncentities-pa.googleapis.com")
}

/** No server messages, request bodies or account identifiers are retained in an exception. */
class PasswordRpcException(val code: Int, val outcomeUnknown: Boolean) : Exception("Password RPC failed (status $code)")

fun interface PasswordTransport { fun invoke(method: PasswordMethod, request: ByteArray): ByteArray }

class GooglePasswordTransport(
    private val token: () -> String,
    private val endpoint: HttpUrl,
    client: OkHttpClient = OkHttpClient(),
    private val rejectedToken: ((String) -> Unit)? = null
) : PasswordTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()

    init {
        require(endpoint.scheme == "https" && endpoint.host in PasswordMethod.values().map { it.host }.toSet() && endpoint.port == 443)
    }

    override fun invoke(method: PasswordMethod, request: ByteArray): ByteArray {
        if (request.size > Proto.MAX_BYTES) throw ProtocolException()
        // A method cannot redirect the account token to a different service or origin.
        if (endpoint.host != method.host) throw ProtocolException()
        val framed = ByteBuffer.allocate(5 + request.size).put(0).putInt(request.size).put(request).array()
        val url = endpoint.newBuilder().encodedPath("/${method.service}/${method.rpc}").build()
        repeat(2) { attempt ->
            val authorization = token()
            try { return execute(method, url, framed, authorization) }
            catch (failure: PasswordRpcException) {
                // A definite authentication refusal proves that the request had no effect.
                // Transport failures and uncertain commit results are never replayed here.
                if (failure.code != 16 || failure.outcomeUnknown || rejectedToken == null) throw failure
                rejectedToken.invoke(authorization)
                if (attempt != 0) throw failure
            }
        }
        throw AssertionError("Unreachable authentication retry")
    }

    private fun execute(method: PasswordMethod, url: HttpUrl, framed: ByteArray, authorization: String): ByteArray {
        val call = Request.Builder().url(url).header("Authorization", "Bearer " + authorization)
            .header("TE", "trailers").header("grpc-accept-encoding", "gzip")
            .post(framed.toRequestBody("application/grpc".toMediaType())).build()
        try {
            client.newCall(call).execute().use { response ->
                if (response.code == 401) throw PasswordRpcException(16, false)
                if (response.code == 403) throw PasswordRpcException(7, false)
                if (response.code != 200) throw PasswordRpcException(14, method.mutates)
                val body = response.body ?: throw PasswordRpcException(13, method.mutates)
                val data = boundedRead(body.byteStream())
                val code = (response.trailers()["grpc-status"] ?: response.header("grpc-status"))?.toIntOrNull()
                    ?: throw PasswordRpcException(13, method.mutates)
                if (code != 0) throw PasswordRpcException(code, method.mutates && code in setOf(2, 4, 13, 14))
                return decodeFrame(data, response.header("grpc-encoding"))
            }
        } catch (e: PasswordRpcException) { throw e }
        catch (_: IOException) { throw PasswordRpcException(14, method.mutates) }
    }

    override fun toString(): String = "GooglePasswordTransport[redacted]"

    companion object {
        internal fun decodeFrame(data: ByteArray, encoding: String?): ByteArray {
            if (data.size < 5 || data.size > Proto.MAX_BYTES + 5) throw ProtocolException()
            val header = ByteBuffer.wrap(data)
            val compression = header.get().toInt()
            val length = header.int
            if (length != data.size - 5 || length < 0) throw ProtocolException()
            val body = data.copyOfRange(5, data.size)
            return when (compression) {
                0 -> body
                1 -> {
                    if (encoding != "gzip") throw ProtocolException()
                    try { GZIPInputStream(body.inputStream()).use(::boundedRead) } catch (_: IOException) { throw ProtocolException() }
                }
                else -> throw ProtocolException()
            }
        }
        private fun boundedRead(input: java.io.InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > Proto.MAX_BYTES + 5) throw ProtocolException()
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
}

class PasswordApi(private val transport: PasswordTransport) {
    fun metadata(): ByteArray = transport.invoke(PasswordMethod.GET_METADATA, Proto.field(1, "users/me"))
    fun initialize(): ByteArray = transport.invoke(PasswordMethod.CREATE_METADATA, Proto.field(1, "users/me"))
    fun list(parent: String, pageToken: ByteArray? = null): ByteArray = transport.invoke(PasswordMethod.LIST,
        Proto.field(1, checkedParent(parent)) + (pageToken?.let { Proto.field(3, it) } ?: byteArrayOf()))
    fun get(name: String): ByteArray = transport.invoke(PasswordMethod.GET, Proto.field(1, checkedName(name)))
    fun create(parent: String, entity: ByteArray, clientToken: ByteArray = byteArrayOf()): ByteArray = transport.invoke(PasswordMethod.CREATE,
        Proto.field(1, checkedParent(parent)) + Proto.field(2, entity) + Proto.field(4, clientToken))
    fun update(entity: ByteArray, clientToken: ByteArray = byteArrayOf()): ByteArray {
        val name = checkedName(Proto.decode(entity).string(1))
        // GMS 26.11 uses the resource ID, not the vault parent, for each update.
        val item = Proto.field(1, name) + Proto.field(2, entity)
        val response = transport.invoke(PasswordMethod.BATCH_UPDATE, Proto.field(1, item) + Proto.field(3, clientToken))
        return Proto.decode(response).one(1)
    }
    fun delete(name: String, clientToken: ByteArray = byteArrayOf()) {
        transport.invoke(PasswordMethod.DELETE, Proto.field(1, checkedName(name)) + Proto.field(3, clientToken))
    }
    fun listPreferences(parent: String, pageToken: ByteArray? = null): ByteArray = transport.invoke(PasswordMethod.LIST_PREFERENCES,
        Proto.field(1, checkedParent(parent)) + (pageToken?.let { Proto.field(4, it) } ?: byteArrayOf()))
    fun createPreference(parent: String, entity: ByteArray, clientToken: ByteArray = byteArrayOf()): ByteArray =
        transport.invoke(PasswordMethod.CREATE_PREFERENCE,
            Proto.field(1, checkedParent(parent)) + Proto.field(2, entity) + Proto.field(4, clientToken))
    fun updatePreference(entity: ByteArray, clientToken: ByteArray = byteArrayOf()): ByteArray =
        transport.invoke(PasswordMethod.UPDATE_PREFERENCE, Proto.field(1, checkedPreferenceName(Proto.decode(entity).string(1))) +
            Proto.field(2, entity) + Proto.field(4, clientToken))
    override fun toString(): String = "PasswordApi[redacted]"

    companion object {
        fun checkedParent(value: String): String {
            if (!Regex("users/me/birthdays/[A-Za-z0-9_.%:-]{1,256}").matches(value)) throw ProtocolException()
            return value
        }
        fun checkedName(value: String): String {
            val split = value.indexOf("/passwords/")
            if (split < 0 || value.length > 4096 || value.any { it.isISOControl() }) throw ProtocolException()
            checkedParent(value.substring(0, split))
            if (value.substring(split + 11).isEmpty() || '/' in value.substring(split + 11)) throw ProtocolException()
            return value
        }
        fun checkedPreferenceName(value: String): String {
            if (value.length > 4096 || value.any { it.isISOControl() }) throw ProtocolException()
            val parts = value.split('/')
            if (parts.size != 6 || !Regex("[A-Za-z][A-Za-z0-9_]{0,63}").matches(parts[4]) || parts[5].isEmpty()) throw ProtocolException()
            checkedParent(parts.take(4).joinToString("/"))
            return value
        }
    }
}
