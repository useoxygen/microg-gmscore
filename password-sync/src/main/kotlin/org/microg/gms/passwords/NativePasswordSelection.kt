package org.microg.gms.passwords

/** A native picker selection is bound to stable account identity, never an email/name alone. Contains no secret. */
class NativePasswordSelection(val accountName: String?, private val userId: String?, val entryId: String) {
    init {
        require(entryId.isNotEmpty() && entryId.length <= 4096)
        require(accountName == null && userId == null || !accountName.isNullOrBlank() && !userId.isNullOrBlank())
    }
    fun matches(currentAccountName: String?, currentUserId: String?): Boolean =
        accountName == currentAccountName && userId == currentUserId
    override fun toString() = "NativePasswordSelection[redacted]"
}
