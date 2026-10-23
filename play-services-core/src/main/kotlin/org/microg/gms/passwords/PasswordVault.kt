/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

interface PasswordVault {
    val enabled: Boolean
    fun setEnabled(value: Boolean)
    fun refresh(): SyncReceipt
    fun initializeEmptyVault()
    fun list(ensureInitialSync: Boolean = false): List<PasswordView>
    fun acceptsAutomaticSave(realm: String): Boolean = enabled && list().none { it.data.blocked && it.data.realm == realm }
    fun upsert(chromePassword: ByteArray)
    fun upsertAutomatic(chromePassword: ByteArray)
    fun delete(chromePassword: ByteArray)
    fun setCustomKey(encryption: ByteArray, mac: ByteArray)
    fun unlockPassphrase(password: ByteArray)
    fun getPreference(name: String): String?
    fun setPreference(name: String, value: String)
}

class PasswordSaveBlockedException : Exception("Automatic password saving is disabled for this site")
