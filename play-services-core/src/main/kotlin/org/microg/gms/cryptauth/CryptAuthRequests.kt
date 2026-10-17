// Modified by Cyclon in 2026: Workspace screen lock proof (enrolled user key, typed HTTP errors, host-testable requests).
package org.microg.gms.cryptauth

import android.content.Context
import android.util.Log
import com.google.android.gms.BuildConfig
import cryptauthv2.ApplicationSpecificMetadata
import cryptauthv2.ClientAppMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.common.Constants
import org.microg.gms.common.DeviceConfiguration
import org.microg.gms.common.Utils
import org.microg.gms.profile.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val TAG = "CryptAuthRequests"

private const val CRYPTAUTH_BASE_URL = "https://cryptauthenrollment.googleapis.com/"
private const val CRYPTAUTH_METHOD_SYNC_KEYS = "v1:syncKeys"
private const val CRYPTAUTH_METHOD_ENROLL_KEYS = "v1:enrollKeys"

private const val CRYPTAUTH_TIMEOUT_MILLIS = 30_000

private const val API_KEY = "AIzaSyAP-gfH3qvi6vgHZbSYwQ_XHqV_mXHhzIk"
internal const val CERTIFICATE = "58E1C4133F7441EC3D2C270270A14802DA47BA0E"

internal const val CRYPTAUTH_FIELD_SESSION_ID = "randomSessionId"
internal const val CRYPTAUTH_FIELD_SYNC_SINGLE_KEY_RESPONSES = "syncSingleKeyResponses"
internal const val CRYPTAUTH_FIELD_KEY_CREATION = "keyCreation"
internal const val CRYPTAUTH_FIELD_KEY_TYPE = "keyType"

internal const val CRYPTAUTH_KEY_NAME_USER_KEY = "PublicKey"

/**
 * Handle sent for the user key bundle before this device enrolled a key of its own. The bytes are
 * `device_key\n`; Chromium uses `device_key` as its fixed user key handle. The value is unchanged
 * (the server answers it with a key creation request); like real handles it is now sent as the
 * proto3 JSON array that the repeated `key_handles` field expects.
 */
private const val PLACEHOLDER_USER_KEY_HANDLE = "ZGV2aWNlX2tleQo="

/** Thrown for a non-200 CryptAuth reply; [code] 429 means Google rate limits this account. */
class CryptAuthHttpException(val code: Int, message: String?) : IOException("HTTP $code: $message")

/** What the SyncKeys reply asks for the user key bundle. */
internal enum class UserKeyInstruction { NONE, CREATE, UNSUPPORTED }

/**
 * Reads the user key bundle's `keyCreation` and `keyType`. Responses come in request order and
 * the request only names the user key bundle. Proto3 JSON may render enums as names or numbers.
 */
internal fun userKeyInstruction(syncKeysResponse: JSONObject): UserKeyInstruction {
    val response = syncKeysResponse.optJSONArray(CRYPTAUTH_FIELD_SYNC_SINGLE_KEY_RESPONSES)?.optJSONObject(0)
        ?: return UserKeyInstruction.NONE
    val creation = response.opt(CRYPTAUTH_FIELD_KEY_CREATION)?.toString()
    if (creation == null || creation == "NONE" || creation == "0") return UserKeyInstruction.NONE
    if (creation != "ACTIVE" && creation != "1" && creation != "INACTIVE" && creation != "2") return UserKeyInstruction.UNSUPPORTED
    val type = response.opt(CRYPTAUTH_FIELD_KEY_TYPE)?.toString()
    // A missing type is not proof of P256, but the user key bundle only holds P256 keys.
    return if (type == null || type == "P256" || type == "4") UserKeyInstruction.CREATE else UserKeyInstruction.UNSUPPORTED
}

internal fun syncSingleKeyRequests(userKey: CryptAuthUserKey?): JSONArray = jsonArrayOf(
    jsonObjectOf(
        "keyName" to CRYPTAUTH_KEY_NAME_USER_KEY,
        "keyHandles" to jsonArrayOf(userKey?.handle?.toByteString()?.base64() ?: PLACEHOLDER_USER_KEY_HANDLE)
    )
)

/**
 * Builds the EnrollKeys body. `randomSessionId` is proto `bytes`, so the proof signs its decoded
 * bytes, as Chromium's `CryptAuthV2EnrollerImpl` does.
 *
 * @throws IllegalArgumentException if a key must be enrolled but [session] is not Base64
 */
internal fun enrollKeysRequest(session: String, newUserKey: CryptAuthUserKey?): JSONObject {
    val enrollSingleKeyRequests = JSONArray()
    if (newUserKey != null) {
        val sessionBytes = requireNotNull(session.decodeBase64()) { "randomSessionId is not Base64" }.toByteArray()
        enrollSingleKeyRequests.put(
            jsonObjectOf(
                "keyName" to CRYPTAUTH_KEY_NAME_USER_KEY,
                "newKeyHandle" to newUserKey.handle.toByteString().base64(),
                "keyMaterial" to newUserKey.keyMaterial.toByteString().base64(),
                "keyProof" to newUserKey.computeKeyProof(sessionBytes).toByteString().base64()
            )
        )
    }
    return jsonObjectOf(
        CRYPTAUTH_FIELD_SESSION_ID to session,
        "clientEphemeralDh" to "",
        "enrollSingleKeyRequests" to enrollSingleKeyRequests,
    )
}

internal suspend fun Context.cryptAuthSyncKeys(authToken: String, instanceId: String, instanceToken: String, androidId: Long, userKey: CryptAuthUserKey?): JSONObject? {
    // CryptAuth sync request tells server whether or not screenlock is enabled

    val deviceConfig = DeviceConfiguration(this)
    val clientAppMetadata = ClientAppMetadata(
        application_specific_metadata = listOf(
            ApplicationSpecificMetadata(
                gcm_registration_id = instanceToken.toByteArray().toByteString(),
                notification_enabled = true,
                device_software_version = "%09d".format(BuildConfig.VERSION_CODE).let {
                    "${it.substring(0, 2)}.${it.substring(2, 4)}.${it.substring(4, 6)} (190800-{{cl}})"
                },
                device_software_version_code = BuildConfig.VERSION_CODE.toLong(),
                device_software_package = Constants.GMS_PACKAGE_NAME
            )
        ),
        instance_id = instanceId,
        instance_id_token = instanceToken,
        android_device_id = androidId,
        locale = Utils.getLocale(this).toString().replace("_", "-"),
        device_os_version = Build.DISPLAY ?: "",
        device_os_version_code = Build.VERSION.SDK_INT.toLong(),
        device_os_release = Build.VERSION.RELEASE ?: "",
        device_os_codename = Build.VERSION.CODENAME ?: "",
        device_display_diagonal_mils = (deviceConfig.diagonalInch * 1000).roundToInt(),
        device_model = Build.MODEL ?: "",
        device_manufacturer = Build.MANUFACTURER ?: "",
        device_type = ClientAppMetadata.DeviceType.ANDROID,
        using_secure_screenlock = isLockscreenConfigured(),
        bluetooth_radio_supported = true, // TODO actual value? doesn't seem relevant
        // bluetooth_radio_enabled = false,
        ble_radio_supported = true, // TODO: actual value? doesn't seem relevant
        mobile_data_supported = true, // TODO: actual value? doesn't seem relevant
        // droid_guard_response = "…"
    )
        .encodeByteString()
        .base64Url()

    val jsonBody = jsonObjectOf(
        "applicationName" to Constants.GMS_PACKAGE_NAME,
        "clientVersion" to "1.0.0",
        "syncSingleKeyRequests" to syncSingleKeyRequests(userKey),
        "clientMetadata" to jsonObjectOf(
            "invocationReason" to "NEW_ACCOUNT"
        ),
        "clientAppMetadata" to clientAppMetadata,
    )

    return cryptAuthQuery(CRYPTAUTH_BASE_URL + CRYPTAUTH_METHOD_SYNC_KEYS, authToken, jsonBody)
}

internal suspend fun Context.cryptAuthEnrollKeys(authToken: String, session: String, newUserKey: CryptAuthUserKey?): JSONObject? =
    cryptAuthQuery(CRYPTAUTH_BASE_URL + CRYPTAUTH_METHOD_ENROLL_KEYS, authToken, enrollKeysRequest(session, newUserKey))

private suspend fun Context.cryptAuthQuery(url: String, authToken: String, requestBody: JSONObject) = withContext(
    Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestMethod("POST")
        setDoInput(true)
        setDoOutput(true)
        setRequestProperty("x-goog-api-key", API_KEY)
        setRequestProperty("x-android-package", Constants.GMS_PACKAGE_NAME)
        setRequestProperty("x-android-cert", CERTIFICATE)
        setRequestProperty("Authorization", "Bearer $authToken")
        setRequestProperty("Content-Type", "application/json")
        connectTimeout = CRYPTAUTH_TIMEOUT_MILLIS
        readTimeout = CRYPTAUTH_TIMEOUT_MILLIS
    }

    // Bodies carry registration ids, keys and proofs: even verbose logging shows only their shape.
    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "-- Request -- ${jsonShape(requestBody)}")
    val os = connection.outputStream
    os.write(requestBody.toString().toByteArray())
    os.close()

    val responseCode = connection.getResponseCode()
    if (responseCode != 200) {
        var error = connection.getResponseMessage()
        try {
            connection.errorStream?.let { error = String(Utils.readStreamToEnd(it)) }
        } catch (e: IOException) {
            // Ignore
        }
        throw CryptAuthHttpException(responseCode, error)
    }

    val result = String(Utils.readStreamToEnd(connection.inputStream))
    val response = try {
        JSONObject(result)
    } catch (e: Exception) {
        null
    }
    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "-- Response -- ${response?.let { jsonShape(it) } ?: "unreadable, ${result.length} chars"}")
    response
}

/** A JSON body's top-level field names and size, never its values. */
internal fun jsonShape(json: JSONObject): String =
    "fields=${json.keys().asSequence().sorted().joinToString(",", "[", "]")} chars=${json.toString().length}"


fun <K, V> jsonObjectOf(vararg pairs: Pair<K, V>): JSONObject = JSONObject(mapOf(*pairs))
inline fun <reified T> jsonArrayOf(vararg elements: T): JSONArray = JSONArray(arrayOf(*elements))