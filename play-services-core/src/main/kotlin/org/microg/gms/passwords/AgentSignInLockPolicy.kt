/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Core attests an owned background target. This never grants access to a login; AgentSignInAccess does that. */
internal object AgentSignInLockPolicy {
    fun targetReady(userUnlocked: Boolean, locked: Boolean, background: Boolean) = userUnlocked && (!locked || background)
    /** With once=false the grant transaction independently requires the current owner's Always policy. */
    fun deliveryAllowed(locked: Boolean, background: Boolean, once: Boolean, requiresAuthentication: Boolean) =
        !locked || background && !once && !requiresAuthentication
}
