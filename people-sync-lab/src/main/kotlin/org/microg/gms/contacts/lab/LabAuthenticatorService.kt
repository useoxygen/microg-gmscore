/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.*
import android.app.Service
import android.content.Intent
import android.os.Bundle

/** Own synthetic provider accounts only. Never authenticates Google accounts. */
class LabAuthenticatorService : Service() {
    private val authenticator by lazy {
        object : AbstractAccountAuthenticator(this) {
            private fun unsupported() = Bundle().apply { putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION) }
            override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = unsupported()
            override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?) = unsupported()
            override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = unsupported()
            override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = unsupported()
            override fun getAuthTokenLabel(authTokenType: String) = ""
            override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = unsupported()
            override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
        }
    }
    override fun onBind(intent: Intent) = authenticator.iBinder
}
