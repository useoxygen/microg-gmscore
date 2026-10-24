/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CancellationException

enum class PrivatePeopleScope(val scope: String) {
    READONLY("https://www.googleapis.com/auth/peopleapi.readonly"),
    READWRITE("https://www.googleapis.com/auth/peopleapi.readwrite"),
    LEGACY_READWRITE("https://www.googleapis.com/auth/peopleapi.legacy.readwrite")
}
enum class ProbeIdentity { ACTUAL, GMS_APP }
enum class ProbeAuthState { TOKEN, CONSENT_REQUIRED, ERROR, NETWORK, UNKNOWN }
data class PrivatePeopleVariant(val scope: PrivatePeopleScope, val identity: ProbeIdentity)
// Deliberately not a data class: debug/toString must never render a token.
class ProbeGrant(val token: String?, val diagnostic: String?)
class ProbeResponseTooLarge(val status: Int) : IOException("Probe response too large")

/** Diagnostic-only capabilities: authorization and one fixed GET, no store or scheduler. */
object PrivatePeopleProbe {
    const val OPTION_PROBE = "org.microg.contacts.PRIVATE_PEOPLE_PROBE"
    const val OPTION_GMS_APP = "org.microg.contacts.PRIVATE_PEOPLE_GMS_APP"
    const val RESULT_AUTH = "org.microg.contacts.PRIVATE_PEOPLE_AUTH"
    const val MAX_RESPONSE_BYTES = 64 * 1024
    val variants: List<PrivatePeopleVariant> = ProbeIdentity.values().flatMap { identity ->
        PrivatePeopleScope.values().map { PrivatePeopleVariant(it, identity) }
    }
    // Discovery revision 20261020. The response selector excludes all people,
    // including metadata: only opaque cursor presence is inspected in RAM.
    val url = "https://people-pa.googleapis.com/v2/people/sync?" + linkedMapOf(
        "pageSize" to "1", "syncParameters.fullSyncOption" to "FULL_SYNC_OK",
        "mergedPersonSourceOptions.personModelParams.personModel" to "CONTACT_CENTRIC",
        "requestMask.includeField" to "person.metadata", "fields" to "nextPageToken,nextSyncToken"
    ).entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

    @JvmStatic fun isPrivateService(service: String?): Boolean = PrivatePeopleScope.values().any { service == "oauth2:" + it.scope }
    @JvmStatic fun isContactsService(service: String?): Boolean = isPrivateService(service) || service in setOf(
        "oauth2:https://www.googleapis.com/auth/contacts", "oauth2:https://www.googleapis.com/auth/contacts.readonly")
    private fun signature(value: String?): String = value?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.uppercase(Locale.ROOT) ?: "UNKNOWN"
    private fun pkg(value: String?): String = if (value == "com.google.android.gms") value else "UNKNOWN"
    private fun http(value: Int?): String = value?.takeIf { it in 100..599 }?.toString() ?: "UNKNOWN"
    @JvmStatic fun authReceipt(state: ProbeAuthState, status: Int?, error: String?, client: String?, clientSig: String?, caller: String?, callerSig: String?): String = JSONObject()
        .put("state", state.name).put("http", status?.takeIf { it in 100..599 } ?: JSONObject.NULL)
        .put("error", ContactsDiagnostics.authError(error)).put("client", pkg(client)).put("clientSig", signature(clientSig))
        .put("caller", pkg(caller)).put("callerSig", signature(callerSig)).toString()
    private fun authDisplay(receipt: String?): String {
        val json = runCatching { JSONObject(receipt ?: "{}") }.getOrDefault(JSONObject())
        val state = json.optString("state").takeIf { value -> ProbeAuthState.values().any { it.name == value } } ?: "UNKNOWN"
        val status = (json.opt("http") as? Int)?.let(::http) ?: "UNKNOWN"
        return "auth=$state auth_http=$status auth_error=${ContactsDiagnostics.authError(json.optString("error"))}" +
            " client=${pkg(json.optString("client"))} client_sig=${signature(json.optString("clientSig"))}" +
            " caller=${pkg(json.optString("caller"))} caller_sig=${signature(json.optString("callerSig"))}"
    }
    /** Return only enums, HTTP status and cursor-presence booleans. Never keep the JSON object. */
    fun apiDisplay(reply: HttpReply): String {
        val base = "http=${http(reply.status)}"
        if (reply.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return "$base api_result=OVERSIZE"
        if (reply.status !in 200..299) return "$base api_status=${ContactsDiagnostics.apiStatus(reply.body) ?: "UNKNOWN"} reason=${ContactsDiagnostics.apiReason(reply.body) ?: "UNKNOWN"}"
        val json = runCatching { JSONObject(reply.body) }.getOrNull() ?: return "$base api_result=INVALID_RESPONSE"
        fun present(key: String) = (json.opt(key) as? String)?.isNotBlank() == true
        return "$base api_result=SUCCESS page_token_present=${present("nextPageToken")} sync_token_present=${present("nextSyncToken")}"
    }
    suspend fun run(authorize: suspend (PrivatePeopleVariant) -> ProbeGrant,
        get: suspend (token: String, url: String) -> HttpReply, emit: (String) -> Unit) {
        for (variant in variants) {
            val grant = try { authorize(variant) } catch (error: CancellationException) { throw error }
                catch (error: Exception) { ProbeGrant(null, null) }
            val api = if (grant.token.isNullOrBlank()) "http=NOT_REQUESTED" else try { apiDisplay(get(grant.token, url)) }
                catch (error: ProbeResponseTooLarge) { "http=${http(error.status)} api_result=OVERSIZE" }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { "http=UNKNOWN api_result=NETWORK" }
            emit("probe=PRIVATE_PEOPLE scope=${variant.scope.name} identity=${variant.identity.name} ${authDisplay(grant.diagnostic)} $api")
        }
    }
}
