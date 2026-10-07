/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import java.io.IOException

enum class ActivationStage { AUTHORIZATION, API, LOCAL }
enum class ActivationFailure {
    PERMISSION, CANCELLED, AUTHORIZATION, NETWORK, API_DENIED, API_UNAVAILABLE,
    RETRY_LATER, ACCOUNT_REMOVED, LOCAL, TIMEOUT
}

class ActivationAccountRemoved : IllegalStateException("Account removed during activation")

// Classify without retaining exception text, tokens, account names or contact data.
fun activationFailure(stage: ActivationStage, error: Exception): ActivationFailure = when (error) {
    is ApiException -> when (error.status) {
        401 -> ActivationFailure.AUTHORIZATION
        403 -> ActivationFailure.API_DENIED
        429 -> ActivationFailure.RETRY_LATER
        else -> ActivationFailure.API_UNAVAILABLE
    }
    is ActivationAccountRemoved -> ActivationFailure.ACCOUNT_REMOVED
    is SecurityException -> if (stage == ActivationStage.LOCAL) ActivationFailure.PERMISSION else ActivationFailure.AUTHORIZATION
    is IOException -> if (stage == ActivationStage.LOCAL) ActivationFailure.LOCAL else ActivationFailure.NETWORK
    else -> when (stage) {
        ActivationStage.AUTHORIZATION -> ActivationFailure.AUTHORIZATION
        ActivationStage.API -> ActivationFailure.API_UNAVAILABLE
        ActivationStage.LOCAL -> ActivationFailure.LOCAL
    }
}
