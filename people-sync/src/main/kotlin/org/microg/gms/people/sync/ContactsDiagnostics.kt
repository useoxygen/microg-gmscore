/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONObject
import java.util.Locale

/** Diagnostics are vocabulary, never server messages or exception text. */
object ContactsDiagnostics {
    private val apiReasons = setOf("EXPIRED_SYNC_TOKEN", "INVALID_PAGE_TOKEN", "SERVICE_DISABLED", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "ACCESS_TOKEN_EXPIRED",
        "CREDENTIALS_MISSING", "ACCESS_TOKEN_TYPE_UNSUPPORTED", "CONSUMER_INVALID", "USER_PROJECT_DENIED",
        "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED", "SECURITY_POLICY_VIOLATED", "API_KEY_SERVICE_BLOCKED",
        "accessNotConfigured", "insufficientPermissions", "forbidden", "authError", "rateLimitExceeded",
        "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "backendError", "invalid", "failedPrecondition", "notFound")
    private val authErrors = setOf("BadAuthentication", "NeedsBrowser", "NeedPermission", "ServiceDisabled",
        "ServiceUnavailable", "UNREGISTERED_ON_API_CONSOLE", "InvalidScope", "Unauthorized", "AccessDenied", "CaptchaRequired",
        "DeviceManagementRequired", "DeviceManagementScreenlockRequired", "DeviceManagementAdminPendingApproval")

    @JvmStatic fun authError(message: String?): String =
        message?.lineSequence()?.firstOrNull()?.removePrefix("Error=")?.takeIf { it in authErrors } ?: "UNKNOWN"

    fun apiReason(body: String): String? = runCatching {
        val error = JSONObject(body).optJSONObject("error") ?: return@runCatching null
        for (key in listOf("details", "errors")) {
            val values = error.optJSONArray(key) ?: continue
            for (index in 0 until values.length()) {
                val reason = values.optJSONObject(index)?.optString("reason")
                if (reason in apiReasons) return@runCatching reason
            }
        }
        null
    }.getOrNull()

    fun apiStatus(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("status")?.takeIf { it in setOf(
            "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "ALREADY_EXISTS",
            "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED",
            "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "DEADLINE_EXCEEDED", "CANCELLED", "UNKNOWN") }
    }.getOrNull()

    fun activation(stage: ActivationStage, error: Exception): String {
        val api = error as? ApiException
        return "stage=${stage.name.lowercase(Locale.ROOT)} failure=${activationFailure(stage, error).name}" +
            (api?.let { " http_status=${it.status} reason=${it.reason ?: "UNKNOWN"} api_code=${it.code ?: "UNKNOWN"}" } ?: "")
    }
}
