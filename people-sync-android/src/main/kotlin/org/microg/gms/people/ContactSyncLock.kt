/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Serializes a contacts sync of one account with removing its synced contacts (same process). */
object ContactSyncLock {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    fun of(account: Account): ReentrantLock = locks.getOrPut(account.type + ":" + account.name) { ReentrantLock() }
}
