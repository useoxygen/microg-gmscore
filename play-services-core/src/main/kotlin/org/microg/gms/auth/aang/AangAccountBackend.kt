/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.aang

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.PendingIntentCompat
import com.google.android.gms.common.api.Status
import org.microg.gms.auth.AskPermissionActivity
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.AuthManagerServiceImpl
import org.microg.gms.auth.AuthResponse
import org.microg.gms.auth.aang.AangParcelables.*
import org.microg.gms.auth.capabilities.CapabilityStore
import org.microg.gms.auth.capabilities.HasCapabilitiesResult
import org.microg.gms.auth.capabilities.UserDataKeys
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class AangAccountBackend(
    private val context: Context,
    private val caller: String,
    private val requestToken: (Account, String) -> AuthResponse = { account, scope ->
        AuthManager(context, account.name, caller, scope).apply { setOauth2Foreground("1") }
            .requestAuthWithBackgroundResolution(false)
    }
) : AangBackend {
    private val accounts = AccountManager.get(context)

    override fun getAccounts(request: GetAccountsRequest): GetAccountsResponse {
        if (request.accountType != AuthConstants.DEFAULT_ACCOUNT_TYPE) unavailable("Unsupported account type")
        // GMS amfb:88-102 supplies AppRestriction states when requested. microG has no such backend.
        if (request.includeRestrictedAccounts || request.includeTransientAccounts) unavailable("Restricted or transient account listing unavailable")
        val features = request.accountFeatures.orEmpty().also { validateNames(it) }
        val selected = if (features.isEmpty()) visibleAccounts() else {
            val matching = accounts.getAccountsByTypeAndFeatures(request.accountType, features.toTypedArray(), null, null)
                .getResult(5, TimeUnit.SECONDS).toSet()
            visibleAccounts().filter { it in matching }
        }
        val caps = request.capabilities.orEmpty().also { validateNames(it) }
        return GetAccountsResponse().apply {
            this.accounts = selected.filter { caps.isEmpty() || capabilityResult(it, caps) == HasCapabilitiesResult.ALLOWED }
                .map { toGoogleAccount(it) }
        }
    }

    override fun getToken(request: GetTokenRequest): GetTokenResponse {
        // GMS amla maps these four fields to oauth2:, weblogin:, ClientLogin and audience: scopes.
        if (request.clientPackageName != null && request.clientPackageName != caller) {
            throw AangFailure(Status(10, "Client package override denied"))
        }
        if (request.delegationType != 0 || request.delegateeUserId != null || request.tokenRequestOptions != null ||
            request.network != null || request.handleNotification) unavailable("Unsupported token request options")
        val types = listOf(request.oauth2Scopes, request.webLoginUrls, request.clientLoginScopes, request.oauth2TokenIdScopes)
        if (types.count { it != null } != 1) throw AangFailure(Status(10, "Specify exactly one token type"))
        val names = types.first { it != null }!!
        // Switch's legacy adapter wraps the entire scope tail in one list element (iim:164-170).
        // Spaces within that element must reach AuthManager's exact scope permission check.
        require(names.size <= 64 && names.all {
            it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) && it.none { ch -> ch.isWhitespace() && ch != ' ' }
        })
        if (names.isEmpty()) throw AangFailure(Status(10, "Missing scopes"))
        val prefix = when {
            request.oauth2Scopes != null -> "oauth2:"
            request.webLoginUrls != null -> "weblogin:"
            request.oauth2TokenIdScopes != null -> "audience:server:client_id:"
            else -> ""
        }
        val account = resolveAccount(request.account, request.gaiaId)
        var scope = prefix + names.joinToString(" ")
        if (scope == AuthConstants.SCOPE_OAUTH2) scope = AuthConstants.SCOPE_EM_OP_PRO
        val response = try { requestToken(account, scope) }
        catch (_: IOException) { throw AangFailure(Status(7, "Token request needs network or account recovery")) }
        val token = response.auth?.takeIf { it.isNotEmpty() } ?: throw AangFailure(
            Status(4, "Account authorization required", permissionResolution(account, scope, response)))
        return GetTokenResponse().apply {
            this.token = token
            metadata = TokenMetadata().apply {
                expiry = response.expiry.takeIf { it > 0 }
                grantedScopes = response.grantedScopes?.split(' ')?.filter { it.isNotEmpty() }
            }
        }
    }

    override fun hasCapabilities(request: HasCapabilitiesRequest): Int {
        val capabilities = request.capabilities.orEmpty().also { validateNames(it) }
        val account = resolveAccount(request.account, null)
        return capabilityResult(account, capabilities)
    }

    private fun visibleAccounts(): List<Account> = accounts.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).filter {
        // Match account-provider visibility without changing the owner's choices. A privileged ACCOUNT caller
        // may use default visibility, but an explicit hidden account stays hidden.
        accounts.getAccountVisibility(it, caller) !in setOf(AccountManager.VISIBILITY_NOT_VISIBLE, AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE)
    }

    private fun toGoogleAccount(account: Account): GoogleAccount = GoogleAccount().apply {
        gaiaId = accounts.getUserData(account, UserDataKeys.GOOGLE_USER_ID)?.takeIf { it.isNotEmpty() }
            ?: throw AangFailure(Status(8, "Account ID unavailable"))
        accountType = account.type
        accountName = account.name
    }

    private fun resolveAccount(request: GoogleAccount?, gaiaId: String?): Account {
        if ((request == null) == gaiaId.isNullOrEmpty()) throw AangFailure(Status(10, "Specify one account"))
        val matches = visibleAccounts().filter { account ->
            val storedId = accounts.getUserData(account, UserDataKeys.GOOGLE_USER_ID)
            if (request != null) account.name == request.accountName && account.type == request.accountType &&
                !storedId.isNullOrEmpty() && storedId == request.gaiaId else storedId == gaiaId
        }
        return matches.singleOrNull() ?: throw AangFailure(Status(5, "Account unavailable"))
    }

    private fun capabilityResult(account: Account, capabilities: List<String>): Int {
        if (capabilities.isEmpty()) return HasCapabilitiesResult.ALLOWED
        val state = CapabilityStore.read(accounts, account)
        for (capability in capabilities) {
            val visibility = state.visibilityByCap[capability]
            if (!visibility.isNullOrEmpty() && caller !in visibility) return HasCapabilitiesResult.VIS_DENIED
            if (capability in state.disabled) return HasCapabilitiesResult.DENIED
            if (capability !in state.enabled) unavailable("Account capability unknown")
        }
        return HasCapabilitiesResult.ALLOWED
    }

    private fun permissionResolution(account: Account, scope: String, response: AuthResponse): PendingIntent? {
        val intent = Intent(context, AskPermissionActivity::class.java).apply {
            putExtra(AuthManagerServiceImpl.KEY_ANDROID_PACKAGE_NAME, caller)
            putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name)
            putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            putExtra(AccountManager.KEY_AUTHTOKEN, scope)
            putExtra(AccountManager.KEY_CALLER_UID, context.packageManager.getApplicationInfo(caller, 0).uid)
            response.consentDataBase64?.let {
                runCatching { Base64.decode(it, Base64.URL_SAFE) }.getOrNull()?.let { data ->
                    putExtra(AskPermissionActivity.EXTRA_CONSENT_DATA, data)
                }
            }
        }
        // The activity rechecks the package/scope grant; a denied request never becomes a token result.
        return PendingIntentCompat.getActivity(context, (account.name + scope + caller).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun validateNames(names: List<String>) {
        require(names.size <= 64 && names.all { it.isNotEmpty() && it.length <= 4096 && it.none(Char::isWhitespace) && it.none(Char::isISOControl) })
    }

    private fun unavailable(message: String): Nothing = throw AangFailure(Status(17, message))
}
