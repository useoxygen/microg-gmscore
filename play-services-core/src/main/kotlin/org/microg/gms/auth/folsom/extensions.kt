/**
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.folsom

import android.accounts.AccountManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.PendingIntentCompat
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.folsom.ui.GenericActivity
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_ACCOUNT_NAME
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_OFFER_RESET
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_OPERATION
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_SECURITY_DOMAIN
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_SESSION_ID
import org.microg.gms.auth.folsom.utils.FolsomDomainPolicy
import org.microg.gms.auth.folsom.utils.FolsomMemberPages
import org.microg.gms.gcm.createGrpcClient

const val ERROR_CODE_NO_KEYS = 38500
const val ERROR_CODE_SECURITY_DOMAIN_NOT_SET = 38501
const val SECURITY_WEB_BASE_URL = "https://accounts.google.com/encryption/unlock/android"
const val SECURITY_DOMAIN_BASE_URL = "https://securitydomain-pa.googleapis.com/"
const val SERVICE_SECURITY_DOMAIN_SCOPE = "oauth2:https://www.googleapis.com/auth/cryptauth"
const val USERS = "users/me"
const val MEMBERS = "/members/"
const val SECURITY_DOMAINS = "/securitydomains/"

enum class DomainStatus(val code: Int) {
    /** Unknown error occurred */
    UNKNOWN_ERROR(1),

    /** Domain state is unknown */
    UNKNOWN(2),

    /** Domain is not recoverable */
    NOT_RECOVERABLE(3),

    /** Recovery is pending */
    PENDING_RECOVERY(4),

    /** Domain is recoverable */
    RECOVERABLE(5),

    /** Recovery is in progress */
    RECOVERY_IN_PROGRESS(6),

    /** No keys available in domain */
    NO_KEYS(7);
}

fun buildKeyDeliveryInfo(sessionId: String, securityDomain: String, offerReset: Boolean): ByteArray =
    KeyDeliveryInfo(
        operationType = KeyDeliveryOperationType.START_KEY_RETRIEVAL,
        keyRetrieval = StartKeyRetrievalRequest(domain = securityDomain, reset = offerReset),
        sessionId = sessionId
    ).encode()

fun serializeDomainStateResponse(state: Int): ByteArray {
    if (state < 128) {
        return byteArrayOf(0x08, state.toByte())
    }
    val result = mutableListOf<Byte>(0x08)
    var v = state
    while (v >= 128) {
        result.add(((v and 0x7F) or 0x80).toByte())
        v = v ushr 7
    }
    result.add(v.toByte())
    return result.toByteArray()
}

fun Context.buildKeyRetrievalStatus(
    accountName: String,
    domainId: String,
    operationType: Int,
    sessionId: String,
    offerReset: Boolean,
    block: (PendingIntent?) -> Status
): Status {
    require(FolsomDomainPolicy.valid(domainId))
    val accountId = folsomAccountId(accountName)
    val intent = Intent(this, GenericActivity::class.java).apply {
        // Immutable tickets for different account/domain sessions must not overwrite one another.
        data = android.net.Uri.Builder().scheme("microg-folsom").authority("recovery")
            .appendPath(accountId).appendPath(domainId).appendPath(sessionId).appendPath(operationType.toString()).build()
        putExtra(EXTRA_ACCOUNT_NAME, accountName)
        putExtra(GenericActivity.EXTRA_ACCOUNT_ID, accountId)
        putExtra(EXTRA_SECURITY_DOMAIN, domainId)
        putExtra(EXTRA_OPERATION, operationType)
        putExtra(EXTRA_SESSION_ID, sessionId)
        putExtra(EXTRA_OFFER_RESET, offerReset)
    }
    return block(PendingIntentCompat.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT, false))
}

fun computeMemberName(publicKeyBytes: ByteArray): String {
    val encoded = Base64.encodeToString(publicKeyBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "$USERS$MEMBERS$encoded"
}

internal fun Context.folsomAccountId(accountName: String): String {
    val manager = AccountManager.get(this)
    val account = manager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).singleOrNull { it.name == accountName }
        ?: throw SecurityException("Recovery account unavailable")
    return manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() }
        ?: throw SecurityException("Recovery identity unavailable")
}

fun Context.requestOauthToken(accountName: String, scope: String = SERVICE_SECURITY_DOMAIN_SCOPE): String {
    val expectedId = folsomAccountId(accountName)
    val accountManager = AccountManager.get(this)
    val account = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).find {
        it.name == accountName
    }
    if (account == null) throw RuntimeException("account is null")
    val token = accountManager.blockingGetAuthToken(account, scope, true)
        ?: throw RuntimeException("oauthToken is null")
    if (folsomAccountId(accountName) != expectedId) throw SecurityException("Recovery account changed")
    return token
}

suspend fun loadSecurityDomainMembers(context: Context, accountName: String, sessionId: String, domainId: String) =
    withContext(Dispatchers.IO) {
        require(FolsomDomainPolicy.valid(domainId))
        val expectedId = context.folsomAccountId(accountName)
        val client = createGrpcClient<SecurityDomainServiceClient>(SECURITY_DOMAIN_BASE_URL, context.requestOauthToken(accountName))
        FolsomMemberPages(expectedId, { context.folsomAccountId(accountName) }) { pageToken ->
            client.ListSecurityDomainMembers().executeBlocking(
                ListSecurityDomainMembersRequest(
                    parent = USERS,
                    view = emptyList(),
                    filter = listOf(USERS + SECURITY_DOMAINS + domainId),
                    memberSelection = 3,
                    pageToken = pageToken,
                    filterOptions = FilterOptions(includeDeleted = true),
                    requestId = sessionId
                )
            )
        }.load()
    }

suspend fun getSecurityDomain(context: Context, accountName: String, sessionId: String, domainId: String) =
    withContext(Dispatchers.IO) {
        createGrpcClient<SecurityDomainServiceClient>(SECURITY_DOMAIN_BASE_URL, context.requestOauthToken(accountName))
            .GetSecurityDomain()
            .executeBlocking(
                GetSecurityDomainRequest(
                    name = USERS + SECURITY_DOMAINS + domainId,
                    view = 2,
                    requestId = sessionId
                )
            )
    }

suspend fun getSecurityDomainMember(context: Context, accountName: String, sessionId: String, domainId: String) =
    withContext(Dispatchers.IO) {
        createGrpcClient<SecurityDomainServiceClient>(SECURITY_DOMAIN_BASE_URL, context.requestOauthToken(accountName))
            .GetSecurityDomainMember()
            .executeBlocking(
                GetSecurityDomainMemberRequest(
                    name = USERS + MEMBERS + domainId,
                    view = 2,
                    filterOptions = FilterOptions(includeDeleted = true),
                    requestId = sessionId
                )
            )
    }
