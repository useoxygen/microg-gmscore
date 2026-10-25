/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context

// Both legacy permissions are declared in the app manifest. SDK35's API-scoped annotation is
// reported by this lint version as literal "1..22;" permission names. Suppress only that one call;
// AccountManager still enforces the caller's permissions, with its original identity and exceptions.
@SuppressLint("MissingPermission")
internal fun invalidateAccountAuthToken(context: Context, accountType: String, authToken: String?) {
    AccountManager.get(context).invalidateAuthToken(accountType, authToken)
}
