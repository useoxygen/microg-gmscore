/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

interface PasswordVault {
    val enabled: Boolean
    fun setEnabled(value: Boolean)
    fun refresh(): SyncReceipt
    fun initializeEmptyVault()
    fun list(ensureInitialSync: Boolean = false): List<PasswordView>
    fun upsert(chromePassword: ByteArray)
    fun delete(chromePassword: ByteArray)
    fun setCustomKey(encryption: ByteArray, mac: ByteArray)
    fun unlockPassphrase(password: ByteArray)
}
