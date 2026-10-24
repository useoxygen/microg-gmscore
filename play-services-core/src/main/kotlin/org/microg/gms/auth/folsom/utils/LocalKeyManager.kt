/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.folsom.utils

import android.annotation.SuppressLint
import android.content.Context
import android.accounts.AccountManager
import android.util.Log
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.integration.android.SharedPrefKeysetReader
import com.google.crypto.tink.integration.android.SharedPrefKeysetWriter
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.DomainStatus
import org.microg.gms.auth.folsom.FolsomKeyStore
import org.microg.gms.auth.folsom.GetSecurityDomainResponse
import org.microg.gms.auth.folsom.KeyPair
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.ListSecurityDomainMembersResponse
import org.microg.gms.auth.folsom.SecurityDomainMemberResponse
import org.microg.gms.auth.folsom.computeMemberName
import org.microg.gms.auth.folsom.loadSecurityDomainMembers
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.io.RandomAccessFile
import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.microg.gms.passwords.EnrollmentState
import org.microg.gms.passwords.EnrollmentPhase
import org.microg.gms.passwords.EnrollmentWire
import org.microg.gms.passwords.DomainLifecycleState
import org.microg.gms.passwords.DomainLifecycleKind
import org.microg.gms.passwords.DomainLifecyclePhase
import org.microg.gms.passwords.DomainLifecycleKey
import org.microg.gms.passwords.DomainLifecycleWire
import org.microg.gms.passwords.RecoverableVaultState
import org.microg.gms.passwords.GpmPinRenewalState
import org.microg.gms.passwords.RecoveryRestoreState
import org.microg.gms.passwords.EnclavePinState

private const val TAG = "LocalKeyManager"
private const val STORE_DIR = "folsom"
private const val STORE_FILE = "FolsomKeyStore.pb"
private const val TINK_KEYSET_PREF_NAME = "folsom_tink_keyset"
private const val TINK_KEYSET_KEY = "folsom_aead_keyset"
private const val TINK_MASTER_KEY_URI = "android-keystore://folsom_tink_master_key"
private const val MAX_KEYS_PER_DOMAIN = 100
private const val TIMESTAMP_JITTER_RANGE = 60000L

private object StoredStatus {
    const val UNKNOWN = 0
    const val NOT_RECOVERABLE = 1
    const val PENDING_RECOVERY = 2
    const val RECOVERABLE = 3
}

class TinkEncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LocalKeyManager(private val context: Context) {

    private val storeFile: File by lazy {
        File(context.filesDir, STORE_DIR).apply { mkdirs() }.resolve(STORE_FILE)
    }

    private val random = Random()

    private val storeLock = Any()

    private val aead: Aead by lazy {
        if (android.os.Build.VERSION.SDK_INT < 23) throw TinkEncryptionException("Keystore protection unavailable")
        runCatching {
            AeadConfig.register()
            val exists = context.getSharedPreferences(TINK_KEYSET_PREF_NAME, Context.MODE_PRIVATE).contains(TINK_KEYSET_KEY)
            val legacyPlaintext = exists && runCatching {
                CleartextKeysetHandle.read(SharedPrefKeysetReader(context, TINK_KEYSET_KEY, TINK_KEYSET_PREF_NAME))
            }.isSuccess
            // Create a missing master only for a new or verified cleartext keyset. Encrypted
            // prefs with a missing master are key loss, never authority to replace that key.
            val master = if (!exists || legacyPlaintext) AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(TINK_MASTER_KEY_URI)
                else AndroidKeystoreKmsClient().getAead(TINK_MASTER_KEY_URI)
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context, TINK_KEYSET_KEY, TINK_KEYSET_PREF_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(TINK_MASTER_KEY_URI)
                .build()
            if (!manager.isUsingKeystore) throw TinkEncryptionException("Keystore protection unavailable")
            // isUsingKeystore also returns true after Tink reads legacy plaintext prefs.
            // Rewrite with the actual Keystore AEAD and strictly decrypt the persisted bytes.
            FolsomProtectedKeyset.persist(manager.keysetHandle, master,
                SharedPrefKeysetWriter(context, TINK_KEYSET_KEY, TINK_KEYSET_PREF_NAME),
                { SharedPrefKeysetReader(context, TINK_KEYSET_KEY, TINK_KEYSET_PREF_NAME) })
        }.getOrElse { Log.e(TAG, "Failed to initialize AEAD", it); throw TinkEncryptionException("Failed to initialize", it) }
    }

    private val legacyFallbackAead: Aead? by lazy {
        try {
            AeadConfig.register()
            val keysetFile = File(context.filesDir, "$STORE_DIR/tink_keyset.json")
            val file = AtomicFile(keysetFile)
            if (!keysetFile.exists() && !File(keysetFile.path + ".bak").exists()) null else {
                val raw = file.openRead().use(::boundedStoreRead)
                try { FolsomProtectedKeyset.readOrProtectLegacyJson(raw, aead) { encrypted ->
                    writeAtomic(file, encrypted)
                    file.openRead().use(::boundedStoreRead)
                } } finally { raw.fill(0) }
            }
        } catch (failure: Exception) { throw TinkEncryptionException("Legacy recovery keyset unavailable", failure) }
    }

    private val protection by lazy { FolsomKeyProtection({ data, aad -> aead.encrypt(data, aad) },
        { data, aad -> aead.decrypt(data, aad) },
        { data, aad -> legacyFallbackAead?.decrypt(data, aad) ?: throw TinkEncryptionException("Legacy key unavailable") }) }

    private fun readStore(): FolsomKeyStore = lockedStore { readStoreUnlocked() }

    // AtomicFile.openRead may delete a staged .new file, so readers take the writer's OS lock too.
    private fun readStoreUnlocked(): FolsomKeyStore {
        if (!storeFile.exists() && !File(storeFile.path + ".bak").exists()) return FolsomKeyStore()
        legacyFallbackAead // Protect old standalone keysets before accepting any store contents.
        val raw = AtomicFile(storeFile).openRead().use(::boundedStoreRead)
        val encrypted = FolsomStoreEnvelope.protected(raw)
        val plain = if (encrypted) FolsomStoreEnvelope.open(raw, aead) else raw
        try {
            val store = FolsomKeyStore.ADAPTER.decode(plain)
            // Preserve/quarantine all legacy provenance, but never leave its raw keys on new writes.
            if (!encrypted) writeStore(store)
            return store
        } finally { plain.fill(0) }
    }

    private fun writeStore(store: FolsomKeyStore) = synchronized(storeLock) {
        legacyFallbackAead
        val plain = store.encode()
        val protected = try { FolsomStoreEnvelope.seal(plain, aead) } finally { plain.fill(0) }
        writeAtomic(AtomicFile(storeFile), protected)
    }

    private fun writeAtomic(file: AtomicFile, protected: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(protected); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw IOException("Failed to write store", failure) }
    }

    private fun boundedStoreRead(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > FolsomStoreEnvelope.MAX_BYTES + 1024) throw TinkEncryptionException("Recovery store too large")
            output.write(buffer, 0, count)
        }
    }

    private inline fun <T> lockedStore(action: () -> T): T = synchronized(storeLock) {
            if (android.os.Build.VERSION.SDK_INT < 23) throw TinkEncryptionException("Keystore protection unavailable")
            RandomAccessFile(File(context.noBackupFilesDir, "folsom-store.lock"), "rw").use { lock ->
                lock.channel.lock().use {
                    action()
                }
            }
    }

    private inline fun updateStore(transform: (FolsomKeyStore) -> FolsomKeyStore) = lockedStore {
        val before = readStoreUnlocked(); val after = transform(before)
        if (after !== before) writeStore(after)
    }

    private fun FolsomKeyStore.findAccount(obfuscatedId: String): AccountData? = accounts.find { it.key == obfuscatedId }?.value_
    private fun AccountData.findDomain(domainId: String): DomainData? = domains.find { it.key == domainId }?.value_

    private fun pendingReset(domain: DomainData, domainId: String): Boolean {
        val raw = domain.lifecycle?.toByteArray() ?: return false
        return try {
            val state = DomainLifecycleState.decode(raw)
            try {
                val attempt = state.attempt ?: return false
                if (attempt.domain != domainId) true else attempt.kind == DomainLifecycleKind.RESET &&
                    !attempt.authorityCleared && attempt.phase != DomainLifecyclePhase.REFUSED
            } finally { state.destroy() }
        } catch (_: Exception) { true } finally { raw.fill(0) }
    }
    /** Password-cache and direct recovery consumers both honor this durable destructive-operation fence. */
    fun hasPendingDomainReset(accountName: String, domainId: String): Boolean {
        require(FolsomDomainPolicy.valid(domainId))
        val userId = stableAccountId(accountName)
        val account = readStore().findAccount(obfuscateAccountName(accountName))
        if (account == null) {
            if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
            return false
        }
        if (account.googleUserId != null && account.googleUserId != userId) throw TinkEncryptionException("Recovery account changed")
        val domain = account.findDomain(domainId)
        if (domain?.googleUserId != null && domain.googleUserId != userId) throw TinkEncryptionException("Recovery account changed")
        val result = domain?.let { pendingReset(it, domainId) } ?: false
        if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
        return result
    }

    private fun <T> updateEntry(
        list: List<T>,
        keySelector: (T) -> String?,
        key: String,
        newEntry: T,
    ): List<T> {
        val mutable = list.toMutableList()
        val idx = mutable.indexOfFirst { keySelector(it) == key }
        return if (idx >= 0) {
            mutable[idx] = newEntry; mutable
        } else {
            mutable + newEntry
        }
    }

    private fun FolsomKeyStore.updateAccount(obfuscatedId: String, accountData: AccountData): FolsomKeyStore =
        copy(
            accounts = updateEntry(
                accounts, { it.key }, obfuscatedId,
                FolsomKeyStore.AccountsEntry(key = obfuscatedId, value_ = accountData)
            )
        )

    private fun AccountData.updateDomain(domainId: String, domainData: DomainData): AccountData =
        copy(
            domains = updateEntry(
                domains, { it.key }, domainId,
                AccountData.DomainsEntry(key = domainId, value_ = domainData)
            )
        )

    private fun obfuscateAccountName(accountName: String): String =
        MessageDigest.getInstance("SHA-256").digest(accountName.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun DomainStatus.convertStatus(): Int = when (this) {
        DomainStatus.UNKNOWN -> StoredStatus.UNKNOWN
        DomainStatus.NOT_RECOVERABLE -> StoredStatus.NOT_RECOVERABLE
        DomainStatus.PENDING_RECOVERY -> StoredStatus.PENDING_RECOVERY
        DomainStatus.RECOVERABLE -> StoredStatus.RECOVERABLE
        else -> StoredStatus.UNKNOWN
    }

    private fun Int.toDomainStatus(): DomainStatus = when (this) {
        StoredStatus.UNKNOWN -> DomainStatus.UNKNOWN
        StoredStatus.NOT_RECOVERABLE -> DomainStatus.NOT_RECOVERABLE
        StoredStatus.PENDING_RECOVERY -> DomainStatus.PENDING_RECOVERY
        StoredStatus.RECOVERABLE -> DomainStatus.RECOVERABLE
        else -> DomainStatus.UNKNOWN_ERROR
    }

    private fun addJitter(timestamp: Long): Long = if (timestamp == 0L) 0L else timestamp - (random.nextFloat() * TIMESTAMP_JITTER_RANGE).toLong()

    fun getDomainStatus(accountName: String, domainId: String): DomainStatus {
        val obfuscatedId = obfuscateAccountName(accountName)
        val store = readStore()
        val account = store.findAccount(obfuscatedId) ?: return DomainStatus.UNKNOWN
        val domain = account.findDomain(domainId) ?: return DomainStatus.UNKNOWN
        return (domain.recoverabilityStatus ?: 0).toDomainStatus()
    }

    private fun stableAccountId(accountName: String): String {
        val manager = AccountManager.get(context)
        val account = manager.getAccountsByType("com.google").singleOrNull { it.name == accountName }
            ?: throw TinkEncryptionException("Account unavailable")
        return manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() }
            ?: throw TinkEncryptionException("Account identity unavailable")
    }
    fun getKeysForDomain(accountName: String, domainId: String, validateLegacyKeys: ((List<Keys>) -> Boolean)? = null): List<Keys> {
        return keySnapshot(accountName, domainId, validateLegacyKeys).keys
    }
    internal fun recoveryGeneration(accountName: String, domainId: String, expectedUserId: String): Long =
        keySnapshot(accountName, domainId, expectedUserId = expectedUserId).generation
    internal fun recoveredDomainIds(accountName: String, expectedUserId: String, prefix: String): List<String> =
        withRecoveryAuthority(accountName, expectedUserId) {
            val result = FolsomRecoveredDomains.list(readStore().findAccount(obfuscateAccountName(accountName)),
                expectedUserId, prefix, ::pendingReset)
            if (stableAccountId(accountName) != expectedUserId) throw TinkEncryptionException("Recovery account changed")
            result
        }
    private fun keySnapshot(accountName: String, domainId: String, validateLegacyKeys: ((List<Keys>) -> Boolean)? = null,
        expectedUserId: String? = null): FolsomKeySnapshot {
        val obfuscatedId = obfuscateAccountName(accountName)
        val userId = stableAccountId(accountName)
        if (expectedUserId != null && userId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        var result = FolsomKeySnapshot(0, emptyList())
        updateStore { store ->
            val account = store.findAccount(obfuscatedId) ?: return@updateStore store
            val domain = account.findDomain(domainId) ?: return@updateStore store
            if (pendingReset(domain, domainId)) throw TinkEncryptionException("Recovery reset is pending")
            if (account.googleUserId != null && account.googleUserId != userId || domain.googleUserId != null && domain.googleUserId != userId)
                throw TinkEncryptionException("Recovery keys belong to a removed account")
            val recovered = protection.read(domainId, domain.keys)
            val bound = FolsomAccountProtection.canRead(account.googleUserId, domain.googleUserId, userId,
                if (account.googleUserId == null && domain.googleUserId == null) validateLegacyKeys?.invoke(recovered.keys) == true else false)
            // An unbound legacy key remains encrypted and quarantined until authenticated
            // metadata (or new exact-account recovery) establishes its provenance.
            result = FolsomKeySnapshot(FolsomStaleKeyPolicy.generation(domain), if (bound) recovered.keys else emptyList())
            if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Account changed during recovery")
            if (recovered.migrate || bound && domain.googleUserId == null) store.updateAccount(obfuscatedId, account.updateDomain(domainId,
                domain.copy(keys = protection.protect(domainId, recovered.keys), googleUserId = if (bound) userId else domain.googleUserId))) else store
        }
        if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Account changed during recovery")
        return result
    }

    /** A successful stale mark is itself durable, even when no locally readable key remains. */
    fun markKeysStale(accountName: String, domainId: String, expectedUserId: String? = null) {
        val userId = stableAccountId(accountName)
        if (expectedUserId != null && userId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        prepareKeyRefresh(accountName, domainId, userId, expectedGeneration = null, force = true)
    }
    private fun prepareKeyRefresh(accountName: String, domainId: String, userId: String, expectedGeneration: Long?, force: Boolean): FolsomKeySnapshot {
        val obfuscatedId = obfuscateAccountName(accountName)
        var result: FolsomKeySnapshot? = null
        updateStore { store ->
            if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
            val account = store.findAccount(obfuscatedId) ?: AccountData(googleUserId = userId)
            val domain = account.findDomain(domainId) ?: DomainData(googleUserId = userId)
            if (pendingReset(domain, domainId)) throw TinkEncryptionException("Recovery reset is pending")
            if (account.googleUserId != null && account.googleUserId != userId || domain.googleUserId != null && domain.googleUserId != userId)
                throw TinkEncryptionException("Recovery keys belong to a removed account")
            val current = FolsomStaleKeyPolicy.generation(domain)
            if (expectedGeneration != null && current != expectedGeneration) throw TinkEncryptionException("Recovery refresh changed")
            val marked = if (force || !FolsomStaleKeyPolicy.stale(current)) FolsomStaleKeyPolicy.mark(domain) else domain
            result = FolsomKeySnapshot(FolsomStaleKeyPolicy.generation(marked), emptyList())
            if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
            if (marked === domain) store else store.updateAccount(obfuscatedId, account.updateDomain(domainId, marked))
        }
        if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
        return result ?: throw TinkEncryptionException("Recovery refresh unavailable")
    }

    private fun hasValidKeys(accountName: String, domainId: String): Boolean = runCatching {
        getKeysForDomain(accountName, domainId).let { it.isNotEmpty() && it.any { (it.keyVersion ?: 0) != 0 } }
    }.getOrElse { Log.e(TAG, "Error checking hasValidKeys", it); false }

    fun setDomainStatus(accountName: String, domainId: String, status: DomainStatus) {
        val obfuscatedId = obfuscateAccountName(accountName)
        updateStore { store ->
            val account = store.findAccount(obfuscatedId) ?: AccountData()
            val domain = account.findDomain(domainId) ?: DomainData()
            if (pendingReset(domain, domainId)) throw TinkEncryptionException("Recovery reset is pending")
            val updatedDomain = domain.copy(
                recoverabilityStatus = status.convertStatus(),
                recoverabilityStatusTimestamp = addJitter(System.currentTimeMillis())
            )
            store.updateAccount(obfuscatedId, account.updateDomain(domainId, updatedDomain))
        }
    }

    fun updateLastFetchTimestamp(accountName: String, domainId: String, timestamp: Long) {
        val obfuscatedId = obfuscateAccountName(accountName)
        updateStore { store ->
            val account = store.findAccount(obfuscatedId) ?: AccountData()
            val domain = account.findDomain(domainId) ?: DomainData()
            store.updateAccount(obfuscatedId, account.updateDomain(domainId, domain.copy(lastFetchTimestamp = addJitter(timestamp))))
        }
    }

    fun clearDomainKeys(accountName: String, domainId: String) {
        val obfuscatedId = obfuscateAccountName(accountName)
        updateStore { store ->
            val account = store.findAccount(obfuscatedId) ?: return@updateStore store
            val domain = account.findDomain(domainId) ?: DomainData()
            if (pendingReset(domain, domainId)) throw TinkEncryptionException("Recovery reset is pending")
            store.updateAccount(obfuscatedId, account.updateDomain(domainId, domain.copy(keys = emptyList())))
        }
    }

    fun saveKeysForDomain(accountName: String, domainId: String, keys: List<Keys>, callerPackage: String? = null, expectedUserId: String? = null) {
        writeKeysForDomain(accountName, domainId, keys, expectedUserId)
    }
    private fun writeKeysForDomain(accountName: String, domainId: String, keys: List<Keys>, expectedUserId: String?, expectedGeneration: Long? = null) {
        val obfuscatedId = obfuscateAccountName(accountName)
        val userId = stableAccountId(accountName)
        if (expectedUserId != null && expectedUserId != userId) throw TinkEncryptionException("Recovery account changed")
        val keysToSave = keys.takeLast(MAX_KEYS_PER_DOMAIN).also {
            if (keys.size > MAX_KEYS_PER_DOMAIN) Log.w(TAG, "Truncating keys from ${keys.size} to $MAX_KEYS_PER_DOMAIN")
        }
        if (!FolsomStaleKeyPolicy.usable(keysToSave)) throw TinkEncryptionException("Fresh recovery keys unavailable")
        updateStore { store ->
            val account = store.findAccount(obfuscatedId)?.takeIf { it.googleUserId == null || it.googleUserId == userId } ?: AccountData(googleUserId = userId)
            val domain = account.findDomain(domainId) ?: DomainData()
            if (pendingReset(domain, domainId)) throw TinkEncryptionException("Recovery reset is pending")
            if (expectedGeneration != null && (FolsomStaleKeyPolicy.generation(domain) != expectedGeneration || !FolsomStaleKeyPolicy.stale(expectedGeneration)))
                throw TinkEncryptionException("Recovery refresh changed")
            store.updateAccount(
                obfuscatedId, account.updateDomain(
                    domainId, FolsomStaleKeyPolicy.fresh(domain).copy(
                        keys = encryptKeysIfNeeded(domainId, keysToSave),
                        googleUserId = userId,
                        lastModifiedTimestamp = System.currentTimeMillis(),
                        lastFetchTimestamp = addJitter(System.currentTimeMillis())
                    )
                )
            ).also { if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Account changed during recovery") }
        }
    }

    private fun encryptKeysIfNeeded(domainId: String, keys: List<Keys>): List<Keys> {
        return protection.protect(domainId, keys)
    }

    private fun getAccountKeyPairs(accountName: String): List<KeyPair> {
        val obfuscatedId = obfuscateAccountName(accountName)
        val userId = stableAccountId(accountName)
        return readStore().findAccount(obfuscatedId)?.takeIf { it.googleUserId == userId }?.accountKeyPairs ?: emptyList()
    }

    /** Account-local OS lock spans enrollment I/O; the shared key-store lock spans only read/write. */
    internal fun <T> withChromesyncEnrollment(accountName: String, expectedUserId: String,
        action: (ChromesyncEnrollmentSession) -> T): T {
        if (android.os.Build.VERSION.SDK_INT < 23) throw TinkEncryptionException("Keystore protection unavailable")
        val lockName = obfuscateAccountName(expectedUserId)
        return FolsomAccountLock.withLock(context.noBackupFilesDir, lockName) {
            if (stableAccountId(accountName) != expectedUserId) throw TinkEncryptionException("Recovery account changed")
            val session = ChromesyncEnrollmentSession(accountName, expectedUserId)
            try { action(session) } finally { session.destroy() }
        }
    }

    internal inner class ChromesyncEnrollmentSession(private val accountName: String, private val expectedUserId: String) {
        private val obfuscatedId = obfuscateAccountName(accountName)
        private val openedPairs = ArrayList<ByteArray>()
        private fun retain(pair: ByteArray) = pair.also { openedPairs.add(it) }
        internal fun destroy() { openedPairs.forEach { it.fill(0) }; openedPairs.clear() }
        private fun verify() {
            if (stableAccountId(accountName) != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        }
        private fun checked(account: AccountData?): AccountData {
            if (account?.googleUserId != null && account.googleUserId != expectedUserId ||
                account?.googleUserId == null && !account?.accountKeyPairs.isNullOrEmpty())
                throw TinkEncryptionException("Recovery key provenance unavailable")
            return account ?: AccountData(googleUserId = expectedUserId)
        }
        fun load(): EnrollmentState {
            verify()
            val account = checked(readStore().findAccount(obfuscatedId))
            val raw = account.chromesyncEnrollment?.toByteArray() ?: byteArrayOf()
            return try { EnrollmentState.decode(raw).also { it.pair?.let(::retain); verify() } } finally { raw.fill(0) }
        }
        fun existingPair(): ByteArray? {
            verify()
            val account = checked(readStore().findAccount(obfuscatedId))
            val pairs = account.accountKeyPairs.filter { it.keyPairType == 1 }
            if (pairs.size > 1) throw TinkEncryptionException("Ambiguous recovery key")
            return pairs.singleOrNull()?.let { key ->
                val private = key.privateKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                val public = key.publicKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                try { buildKeyPairBytes(private, public).also { EnrollmentWire.checkPair(it); verify(); retain(it) } }
                finally { private.fill(0); public.fill(0) }
            }
        }
        fun save(state: EnrollmentState) {
            verify()
            state.pair?.let { EnrollmentWire.checkPair(it); retain(it) }
            val encoded = state.encode()
            try {
                updateStore { store ->
                    verify()
                    val account = FolsomAccountProtection.bind(checked(store.findAccount(obfuscatedId)), expectedUserId)
                    val previousRaw = account.chromesyncEnrollment?.toByteArray() ?: byteArrayOf()
                    val previous = try { EnrollmentState.decode(previousRaw) } finally { previousRaw.fill(0) }
                    try {
                        if (previous.pair != null && (state.pair == null || !MessageDigest.isEqual(previous.pair, state.pair)))
                            throw TinkEncryptionException("Recovery key changed")
                        var pairs = account.accountKeyPairs
                        val attempt = state.attempt
                        if (attempt?.phase == EnrollmentPhase.ENROLLED) {
                            val pair = state.pair ?: throw TinkEncryptionException("Recovery key unavailable")
                            if (attempt.accountId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
                            val public = EnrollmentWire.publicKey(pair)
                            val existing = pairs.filter { it.keyPairType == 1 }
                            if (existing.size > 1) throw TinkEncryptionException("Ambiguous recovery key")
                            existing.singleOrNull()?.let { key ->
                                val private = key.privateKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                                val storedPublic = key.publicKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                                val known = try { buildKeyPairBytes(private, storedPublic) } finally { private.fill(0); storedPublic.fill(0) }
                                try { if (!MessageDigest.isEqual(known, pair)) throw TinkEncryptionException("Recovery key changed") }
                                finally { known.fill(0) }
                            }
                            if (existing.isEmpty()) pairs = pairs + KeyPair(publicKey = public.toByteString(),
                                privateKey = pair.toByteString(), keyPairType = 1)
                        }
                        verify()
                        store.updateAccount(obfuscatedId, account.copy(googleUserId = expectedUserId,
                            accountKeyPairs = pairs, chromesyncEnrollment = encoded.toByteString()))
                    } finally { previous.pair?.fill(0) }
                }
                verify()
            } finally { encoded.fill(0) }
        }
    }

    internal fun <T> withDomainLifecycle(accountName: String, expectedUserId: String, domainId: String,
        action: (DomainLifecycleSession) -> T): T {
        DomainLifecycleWire.checkedDomain(domainId)
        return withChromesyncEnrollment(accountName, expectedUserId) {
            val session = DomainLifecycleSession(accountName, expectedUserId, domainId)
            try { action(session) } finally { session.destroy() }
        }
    }

    /** Acquire before repository/passkey locks so reset cannot race a credential signature or creation. */
    internal fun <T> withRecoveryAuthority(accountName: String, expectedUserId: String, action: () -> T): T =
        withChromesyncEnrollment(accountName, expectedUserId) { action() }

    /** Shared account lock and protected opaque journal for the OS recoverable-keystore consumer. */
    internal fun <T> withRecoverableVault(accountName: String, accountId: String,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withRecoveryJournal(accountName, accountId, RecoveryJournal.RECOVERABLE_VAULT, action)

    internal fun <T> withGpmPinRenewal(accountName: String, accountId: String,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withRecoveryJournal(accountName, accountId, RecoveryJournal.GPM_PIN_RENEWAL, action)

    internal fun <T> withRecoveryRestore(accountName: String, accountId: String,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withRecoveryJournal(accountName, accountId, RecoveryJournal.RESTORE, action)

    internal fun <T> withEnclavePin(accountName: String, accountId: String,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withRecoveryJournal(accountName, accountId, RecoveryJournal.ENCLAVE_PIN, action)

    internal fun <T> withFolsomConsent(accountName: String, accountId: String,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withRecoveryJournal(accountName, accountId, RecoveryJournal.CONSENT, action)

    private fun checkedConsentNotificationAccount(account: AccountData?, accountId: String, domainId: String): AccountData {
        require(FolsomDomainPolicy.valid(domainId))
        if (account?.googleUserId != null && account.googleUserId != accountId)
            throw TinkEncryptionException("Recovery account changed")
        for (id in FolsomConsentPolicy.coupled + domainId) account?.findDomain(id)?.let {
            if (it.googleUserId != null && it.googleUserId != accountId)
                throw TinkEncryptionException("Recovery account changed")
            if (pendingReset(it, id)) throw TinkEncryptionException("Recovery reset pending")
        }
        return account ?: AccountData(googleUserId = accountId)
    }

    internal fun consentNotificationEligible(accountName: String, accountId: String, domainId: String,
        nowMillis: Long = System.currentTimeMillis(), cooldownMillis: Long = FolsomConsentNotificationPolicy.DEFAULT_COOLDOWN_MILLIS): Boolean = withRecoveryAuthority(accountName, accountId) {
        val account = checkedConsentNotificationAccount(readStore().findAccount(obfuscateAccountName(accountName)), accountId, domainId)
        FolsomConsentNotificationPolicy.eligible(account, accountId, domainId, nowMillis, cooldownMillis).also {
            if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed")
        }
    }

    /** Invoke after the real notification post; authorization must not acquire recovery/repository locks. */
    internal fun recordConsentNotificationPosted(accountName: String, accountId: String, domainId: String,
        postedAtMillis: Long = System.currentTimeMillis(), authorize: () -> Unit) = withRecoveryAuthority(accountName, accountId) {
        fun verify() { authorize(); if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed") }
        verify(); updateStore { store ->
            verify()
            val obfuscatedId = obfuscateAccountName(accountName)
            val account = checkedConsentNotificationAccount(store.findAccount(obfuscatedId), accountId, domainId)
            val replacement = FolsomConsentNotificationPolicy.posted(account, accountId, domainId, postedAtMillis)
            verify(); store.updateAccount(obfuscatedId, replacement)
        }; verify()
    }

    /** This snapshot contains consent values only, never the account's recovery keys or private pairs. */
    internal fun consentSnapshot(accountName: String, accountId: String): AccountData = withRecoveryAuthority(accountName, accountId) {
        consentSnapshotUnderRecoveryLock(accountName, accountId)
    }
    /** Caller already holds this account's recovery OS lock. Never acquire that lock recursively. */
    internal fun consentSnapshotUnderRecoveryLock(accountName: String, accountId: String): AccountData {
        if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed")
        val account = readStore().findAccount(obfuscateAccountName(accountName))
        if (account?.googleUserId != null && account.googleUserId != accountId) throw TinkEncryptionException("Recovery account changed")
        return AccountData(googleUserId = accountId, domains = account?.domains.orEmpty().mapNotNull { entry ->
            val id = entry.key ?: return@mapNotNull null
            val domain = entry.value_ ?: return@mapNotNull null
            if (!FolsomAccountProtection.canRead(account?.googleUserId, domain.googleUserId, accountId, false)) return@mapNotNull null
            AccountData.DomainsEntry(key = id, value_ = DomainData(googleUserId = accountId, consent = domain.consent))
        }).also { if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed") }
    }
    internal fun consentJournalUnderRecoveryLock(accountName: String, accountId: String): ByteArray {
        if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed")
        val account = readStore().findAccount(obfuscateAccountName(accountName))
        if (account?.googleUserId != accountId) throw TinkEncryptionException("Recovery account changed")
        return account.consentJournal?.toByteArray() ?: byteArrayOf()
    }

    /** CAS the orchestration receipt and selected explicit consent in the same protected root-store update. */
    internal fun compareFolsomConsent(accountName: String, accountId: String, before: FolsomConsentState, after: FolsomConsentState,
        domain: String? = null, value: Boolean? = null, authorize: () -> Unit) = withRecoveryAuthority(accountName, accountId) {
        fun verify() { authorize(); if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed") }
        val expected = before.encode(); val replacement = after.encode()
        try { verify(); updateStore { store ->
            verify()
            val obfuscatedId = obfuscateAccountName(accountName)
            val existing = store.findAccount(obfuscatedId) ?: AccountData(googleUserId = accountId)
            if (existing.googleUserId != null && existing.googleUserId != accountId) throw TinkEncryptionException("Recovery account changed")
            for (id in FolsomConsentPolicy.coupled) existing.findDomain(id)?.let {
                if (pendingReset(it, id)) throw TinkEncryptionException("Recovery reset pending")
            }
            val raw = existing.consentJournal?.toByteArray() ?: byteArrayOf()
            val current = try { FolsomConsentState.decode(raw) } finally { raw.fill(0) }
            try {
                val encoded = current.encode()
                try { if (!MessageDigest.isEqual(encoded, expected)) throw TinkEncryptionException("Recovery consent changed") }
                finally { encoded.fill(0) }
            } finally { current.destroy() }
            var bound = FolsomAccountProtection.bind(existing, accountId).copy(consentJournal = replacement.toByteString())
            if (domain != null && value != null) bound = FolsomConsentPolicy.store(bound, accountId, domain, value)
            verify(); store.updateAccount(obfuscatedId, bound)
        }; verify() } finally { expected.fill(0); replacement.fill(0) }
    }

    private enum class RecoveryJournal { RECOVERABLE_VAULT, GPM_PIN_RENEWAL, RESTORE, ENCLAVE_PIN, CONSENT }

    /** The caller holds withRecoveryRestore across authenticated restore/readback and this atomic commit. */
    internal fun commitRecoveryRestore(accountName: String, accountId: String, domainId: String,
        expectedJournal: ByteArray, replacementJournal: ByteArray, expectedKeys: List<Keys>, recoveredKeys: List<Keys>,
        authorize: () -> Unit) = commitRecoveryRestoreDomains(accountName, accountId, expectedJournal, replacementJournal,
            mapOf(domainId to expectedKeys), mapOf(domainId to recoveredKeys), authorize)

    /** One OS recovery session may restore both domains. Publish their keys and the receipt together. */
    internal fun commitRecoveryRestoreDomains(accountName: String, accountId: String,
        expectedJournal: ByteArray, replacementJournal: ByteArray,
        expectedKeys: Map<String, List<Keys>>, recoveredKeys: Map<String, List<Keys>>, authorize: () -> Unit) {
        if (expectedKeys.isEmpty() || expectedKeys.keys != recoveredKeys.keys || expectedKeys.size > 2)
            throw TinkEncryptionException("Recovery domains unavailable")
        expectedKeys.keys.forEach(DomainLifecycleWire::checkedDomain)
        require(expectedJournal.size <= FolsomStoreEnvelope.MAX_BYTES && replacementJournal.size <= FolsomStoreEnvelope.MAX_BYTES)
        fun verify() {
            // This owner-only callback must not acquire recovery/repository locks. Domain
            // reset and key/journal comparisons are checked directly under this store lock.
            authorize()
            if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed")
        }
        fun checkedKeys(keys: List<Keys>, allowEmpty: Boolean): Map<Int, ByteString> {
            if (keys.size > MAX_KEYS_PER_DOMAIN || !allowEmpty && keys.isEmpty() ||
                keys.any { (it.keyVersion ?: 0) <= 0 || it.keyMaterial?.size?.let { size -> size in 16..4096 } != true } ||
                keys.map { it.keyVersion }.toSet().size != keys.size) throw TinkEncryptionException("Recovery key versions unavailable")
            return keys.associate { it.keyVersion!! to it.keyMaterial!! }
        }
        val expected = expectedKeys.mapValues { checkedKeys(it.value, true) }
        val replacement = recoveredKeys.mapValues { checkedKeys(it.value, false) }
        fun same(first: ByteString, second: ByteString): Boolean {
            val a = first.toByteArray(); val b = second.toByteArray()
            return try { MessageDigest.isEqual(a, b) } finally { a.fill(0); b.fill(0) }
        }
        if (expected.any { (domain, keys) -> keys.any { (epoch, material) ->
            replacement[domain]?.get(epoch)?.let { same(it, material) } != true } })
            throw TinkEncryptionException("Recovery would discard existing keys")
        verify()
        updateStore { store ->
            verify()
            val obfuscatedId = obfuscateAccountName(accountName)
            val account = store.findAccount(obfuscatedId) ?: throw TinkEncryptionException("Recovery journal unavailable")
            if (account.googleUserId != accountId) throw TinkEncryptionException("Recovery account changed")
            for (id in listOf("chromesync", "hw_protected")) account.findDomain(id)?.let {
                if (pendingReset(it, id)) throw TinkEncryptionException("Recovery reset is pending")
            }
            val journal = account.recoveryRestore?.toByteArray() ?: byteArrayOf()
            try { if (!MessageDigest.isEqual(journal, expectedJournal)) throw TinkEncryptionException("Recovery journal changed") }
            finally { journal.fill(0) }
            var updatedAccount = account.copy(recoveryRestore = replacementJournal.toByteString())
            for ((domainId, expectedDomain) in expected) {
                val domain = account.findDomain(domainId) ?: DomainData(googleUserId = accountId)
                if (domain.googleUserId != null && domain.googleUserId != accountId) throw TinkEncryptionException("Recovery account changed")
                val currentRecords = protection.read(domainId, domain.keys).keys
                val current = checkedKeys(currentRecords, true)
                if (current.keys != expectedDomain.keys || current.any { (epoch, material) ->
                    expectedDomain[epoch]?.let { same(it, material) } != true }) throw TinkEncryptionException("Recovery keys changed")
                val retained = recoveredKeys.getValue(domainId).map { key -> currentRecords.singleOrNull { it.keyVersion == key.keyVersion }
                    ?.copy(keyMaterial = key.keyMaterial) ?: key }
                val updated = FolsomStaleKeyPolicy.fresh(domain).copy(keys = protection.protect(domainId, retained),
                    googleUserId = accountId, recoverabilityStatus = StoredStatus.RECOVERABLE,
                    lastFetchTimestamp = System.currentTimeMillis(), lastModifiedTimestamp = System.currentTimeMillis())
                updatedAccount = updatedAccount.updateDomain(domainId, updated)
            }
            verify()
            store.updateAccount(obfuscatedId, updatedAccount)
        }
        verify()
    }

    private fun <T> withRecoveryJournal(accountName: String, accountId: String, journal: RecoveryJournal,
        action: (loadBytes: () -> ByteArray, saveBytes: (ByteArray) -> Unit) -> T): T =
        withChromesyncEnrollment(accountName, accountId) {
            val obfuscatedId = obfuscateAccountName(accountName)
            fun verify() {
                if (stableAccountId(accountName) != accountId) throw TinkEncryptionException("Recovery account changed")
            }
            fun checked(account: AccountData?): AccountData {
                if (account?.googleUserId != null && account.googleUserId != accountId ||
                    account?.googleUserId == null && !account?.accountKeyPairs.isNullOrEmpty())
                    throw TinkEncryptionException("Recovery key provenance unavailable")
                return account ?: AccountData(googleUserId = accountId)
            }
            val opened = ArrayList<ByteArray>()
            val load = {
                verify()
                val account = checked(readStore().findAccount(obfuscatedId))
                val stored = when (journal) {
                    RecoveryJournal.RECOVERABLE_VAULT -> account.recoverableVault
                    RecoveryJournal.GPM_PIN_RENEWAL -> account.gpmPinRenewal
                    RecoveryJournal.RESTORE -> account.recoveryRestore
                    RecoveryJournal.ENCLAVE_PIN -> account.enclavePin
                    RecoveryJournal.CONSENT -> account.consentJournal
                }
                (stored?.toByteArray() ?: byteArrayOf()).also { opened.add(it); verify() }
            }
            val save: (ByteArray) -> Unit = { bytes ->
                require(bytes.size <= FolsomStoreEnvelope.MAX_BYTES)
                verify()
                updateStore { store ->
                    verify()
                    val account = FolsomAccountProtection.bind(checked(store.findAccount(obfuscatedId)), accountId)
                    val replacement = when (journal) {
                        RecoveryJournal.RECOVERABLE_VAULT -> account.copy(recoverableVault = bytes.toByteString())
                        RecoveryJournal.GPM_PIN_RENEWAL -> account.copy(gpmPinRenewal = bytes.toByteString())
                        RecoveryJournal.RESTORE -> account.copy(recoveryRestore = bytes.toByteString())
                        RecoveryJournal.ENCLAVE_PIN -> account.copy(enclavePin = bytes.toByteString())
                        RecoveryJournal.CONSENT -> account.copy(consentJournal = bytes.toByteString())
                    }
                    store.updateAccount(obfuscatedId, replacement).also { verify() }
                }
                verify()
            }
            try { action(load, save) } finally { opened.forEach { it.fill(0) } }
        }
    internal inner class DomainLifecycleSession(private val accountName: String, private val expectedUserId: String,
        private val domainId: String) {
        private val obfuscatedId = obfuscateAccountName(accountName)
        private val opened = ArrayList<DomainLifecycleState>()
        private val pairs = ArrayList<ByteArray>()
        internal fun destroy() { opened.forEach { it.destroy() }; pairs.forEach { it.fill(0) } }
        private fun verify() {
            if (stableAccountId(accountName) != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        }
        private fun checked(account: AccountData?): AccountData {
            if (account?.googleUserId != null && account.googleUserId != expectedUserId ||
                account?.googleUserId == null && !account?.accountKeyPairs.isNullOrEmpty())
                throw TinkEncryptionException("Recovery key provenance unavailable")
            return account ?: AccountData(googleUserId = expectedUserId)
        }
        fun load(): DomainLifecycleState {
            verify()
            val account = checked(readStore().findAccount(obfuscatedId))
            val domain = account.findDomain(domainId)
            if (domain?.googleUserId != null && domain.googleUserId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
            val raw = domain?.lifecycle?.toByteArray() ?: byteArrayOf()
            return try { DomainLifecycleState.decode(raw).also { opened.add(it); verify() } } finally { raw.fill(0) }
        }
        fun existingPair(): ByteArray? {
            verify()
            val account = checked(readStore().findAccount(obfuscatedId))
            val existing = account.accountKeyPairs.filter { it.keyPairType == pairType() }
            if (existing.size > 1) throw TinkEncryptionException("Ambiguous recovery key")
            return existing.singleOrNull()?.let { key ->
                val private = key.privateKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                val public = key.publicKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                try { buildKeyPairBytes(private, public).also { EnrollmentWire.checkPair(it); pairs.add(it); verify() } }
                finally { private.fill(0); public.fill(0) }
            }
        }
        private fun pairType() = if (domainId == "hw_protected") 3 else 1
        fun save(state: DomainLifecycleState) {
            verify(); opened.add(state)
            val attempt = state.attempt
            if (attempt != null && (attempt.accountId != expectedUserId || attempt.domain != domainId)) throw TinkEncryptionException("Recovery account changed")
            state.pair?.let(EnrollmentWire::checkPair)
            val encoded = state.encode()
            try { updateStore { store ->
                verify()
                var account = FolsomAccountProtection.bind(checked(store.findAccount(obfuscatedId)), expectedUserId)
                var domain = account.findDomain(domainId) ?: DomainData(googleUserId = expectedUserId)
                if (domain.googleUserId != null && domain.googleUserId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
                val previousRaw = domain.lifecycle?.toByteArray() ?: byteArrayOf()
                val previous = try { DomainLifecycleState.decode(previousRaw) } finally { previousRaw.fill(0) }
                try {
                    if (previous.pair != null && (state.pair == null || !MessageDigest.isEqual(previous.pair, state.pair)))
                        throw TinkEncryptionException("Recovery key changed")
                    if (attempt?.phase == DomainLifecyclePhase.COMMITTED) {
                        if (attempt.kind == DomainLifecycleKind.RESET) {
                            // Only confirmed server absence clears local recovery authority. The journal fence remains.
                            domain = FolsomStaleKeyPolicy.reset(domain, expectedUserId)
                            if (domainId == "chromesync") account.chromesyncEnrollment?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { FolsomAccountProtection.redactEnrollment(raw) } finally { raw.fill(0) }
                                try { account = account.copy(chromesyncEnrollment = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                            account.recoverableVault?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { RecoverableVaultState.clearDomain(raw, domainId) } finally { raw.fill(0) }
                                try { account = account.copy(recoverableVault = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                            if (domainId == "hw_protected") account.gpmPinRenewal?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { GpmPinRenewalState.clearDomain(raw, domainId) } finally { raw.fill(0) }
                                try { account = account.copy(gpmPinRenewal = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                            account.recoveryRestore?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { RecoveryRestoreState.clearDomain(raw, domainId) } finally { raw.fill(0) }
                                try { account = account.copy(recoveryRestore = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                            if (domainId == "hw_protected") account.enclavePin?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { EnclavePinState.clearDomain(raw, domainId) } finally { raw.fill(0) }
                                try { account = account.copy(enclavePin = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                            account.consentJournal?.let { stored ->
                                val raw = stored.toByteArray()
                                val redacted = try { FolsomConsentState.clearDomain(raw, domainId) } finally { raw.fill(0) }
                                try { account = account.copy(consentJournal = redacted.toByteString()) }
                                finally { redacted.fill(0) }
                            }
                        } else if (attempt.kind != DomainLifecycleKind.ADD_MEMBER) {
                            val pair = state.pair ?: throw TinkEncryptionException("Recovery key unavailable")
                            val oldPairs = account.accountKeyPairs.filter { it.keyPairType == pairType() }
                            if (oldPairs.size > 1) throw TinkEncryptionException("Ambiguous recovery key")
                            oldPairs.singleOrNull()?.let { old ->
                                val private = old.privateKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                                val public = old.publicKey?.toByteArray() ?: throw TinkEncryptionException("Recovery key unavailable")
                                val known = try { buildKeyPairBytes(private, public) } finally { private.fill(0); public.fill(0) }
                                try { if (!MessageDigest.isEqual(known, pair)) throw TinkEncryptionException("Recovery key changed") }
                                finally { known.fill(0) }
                            }
                            if (oldPairs.isEmpty()) account = account.copy(accountKeyPairs = account.accountKeyPairs + KeyPair(
                                publicKey = EnrollmentWire.publicKey(pair).toByteString(), privateKey = pair.toByteString(), keyPairType = pairType()))
                            val material = attempt.knownKeys.map(DomainLifecycleKey::decode).toMutableList()
                            try {
                                attempt.proposedKey?.let { bytes ->
                                    DomainLifecycleKey.decode(bytes).use { material.add(it.atEpoch(attempt.resultEpoch)) }
                                }
                                if (material.isEmpty() || material.size > MAX_KEYS_PER_DOMAIN || material.any { it.epoch <= 0 } ||
                                    material.map { it.epoch }.toSet().size != material.size) throw TinkEncryptionException("Recovery key versions unavailable")
                                val keys = material.sortedBy { it.epoch }.map { key ->
                                    val raw = key.material
                                    try { Keys(keyVersion = key.epoch, keyMaterial = raw.toByteString()) } finally { raw.fill(0) }
                                }
                                domain = FolsomStaleKeyPolicy.fresh(domain).copy(keys = protection.protect(domainId, keys),
                                    googleUserId = expectedUserId, recoverabilityStatus = StoredStatus.RECOVERABLE,
                                    lastFetchTimestamp = System.currentTimeMillis())
                            } finally { material.forEach { it.close() } }
                        }
                    }
                    verify()
                    store.updateAccount(obfuscatedId, account.copy(googleUserId = expectedUserId).updateDomain(domainId,
                        domain.copy(lifecycle = encoded.toByteString(), googleUserId = expectedUserId)))
                } finally { previous.destroy() }
            }; verify() } finally { encoded.fill(0) }
        }
    }

    /** Called by account removal; active accounts are retained, including encrypted unbound migration inputs. */
    fun purgeRemovedAccounts() {
        updateStore { store ->
            val manager = AccountManager.get(context)
            fun discover() = manager.getAccountsByType("com.google").associate { account ->
                obfuscateAccountName(account.name) to manager.getUserData(account, "GoogleUserId")
            }
            val present = discover()
            val retained = FolsomAccountProtection.retainPresent(store, present)
            if (discover() != present) throw TinkEncryptionException("Recovery accounts changed during cleanup")
            retained
        }
    }

    private fun getPhysicalDeviceKeyPair(accountName: String, domainId: String): KeyPair? {
        val keyPairs = getAccountKeyPairs(accountName)
        return if (domainId == "chromesync") keyPairs.singleOrNull { it.keyPairType == 1 }
        else keyPairs.find { it.keyPairType == 3 } ?: keyPairs.find { it.keyPairType == 1 }
    }

    private fun buildKeyPairBytes(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(publicKey.size == 65 && publicKey[0] == 4.toByte() && privateKey.size in setOf(32, 97)) { "Invalid recovery key pair" }
        if (privateKey.size == 97) require(MessageDigest.isEqual(privateKey.copyOfRange(32, 97), publicKey)) { "Recovery key pair changed" }
        return privateKey.copyOfRange(0, 32) + publicKey
    }

    fun computeDomainStatus(
        accountName: String,
        domainId: String,
        serverState: GetSecurityDomainResponse?,
        membersResponse: ListSecurityDomainMembersResponse?
    ): DomainStatus {
        if (serverState == null) {
            val hasLocalKeys = hasValidKeys(accountName, domainId)
            return if (hasLocalKeys) DomainStatus.NOT_RECOVERABLE else DomainStatus.UNKNOWN
        }
        val members = membersResponse?.members
        if (members.isNullOrEmpty()) {
            return DomainStatus.NO_KEYS
        }
        val hasKeys = members.any { member ->
            member.securityDomains.any { sd ->
                FolsomDomainPolicy.matches(sd.name, domainId) && sd.memberKeys.isNotEmpty()
            }
        }
        if (!hasKeys) {
            return DomainStatus.NO_KEYS
        }
        if (hasValidKeys(accountName, domainId)) {
            return DomainStatus.RECOVERABLE
        }
        val localKeyPair = getPhysicalDeviceKeyPair(accountName, domainId)
        if (localKeyPair != null) {
            val localPublicKey = localKeyPair.publicKey?.toByteArray()
            if (localPublicKey != null) {
                val localMemberName = computeMemberName(localPublicKey)
                val isDeviceRegistered = members.any { it.name == localMemberName }
                if (isDeviceRegistered) {
                    return DomainStatus.RECOVERABLE
                }
            }
        }
        return DomainStatus.PENDING_RECOVERY
    }

    suspend fun getLocalKeysOrSync(
        context: Context,
        accountName: String,
        domainId: String,
        sessionId: String,
        forceRefresh: Boolean = false,
        validateLegacyKeys: ((List<Keys>) -> Boolean)? = null,
        expectedUserId: String? = null
    ): List<Keys> = runCatching {
        val userId = stableAccountId(accountName)
        if (expectedUserId != null && userId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        val result = FolsomKeyRefresh(
            read = { keySnapshot(accountName, domainId, validateLegacyKeys, userId) },
            prepare = { snapshot, force -> prepareKeyRefresh(accountName, domainId, userId, snapshot.generation, force) },
            fetch = {
                if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
                val members = loadSecurityDomainMembers(context, accountName, sessionId, domainId)
                if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
                extractKeysFromMembers(members.members, domainId, accountName, keySnapshot(accountName, domainId, validateLegacyKeys, userId).keys)
            },
            commit = { snapshot, keys -> writeKeysForDomain(accountName, domainId, keys, userId, snapshot.generation) }
        ).load(forceRefresh)
        if (stableAccountId(accountName) != userId) throw TinkEncryptionException("Recovery account changed")
        result
    }.getOrDefault(emptyList())

    private fun extractKeysFromMembers(
        members: List<SecurityDomainMemberResponse>,
        domainId: String,
        accountName: String,
        known: List<Keys>
    ): List<Keys> = getPhysicalDeviceKeyPair(accountName, domainId)
        ?.let { keyPair ->
            val pubKey = keyPair.publicKey?.toByteArray()
            val priKey = keyPair.privateKey?.toByteArray()
            if (pubKey != null && priKey != null) {
                val pair = buildKeyPairBytes(priKey, pubKey)
                try { members.singleOrNull { it.name == computeMemberName(pubKey) }
                    ?.let { FolsomMemberKeys.decode(it, domainId, pair, known) } }
                finally { pair.fill(0); priKey.fill(0); pubKey.fill(0) }
            } else null
        } ?: emptyList()

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LocalKeyManager? = null
        fun getInstance(context: Context): LocalKeyManager = instance ?: synchronized(this) {
            instance ?: LocalKeyManager(context.applicationContext).also { instance = it }
        }
    }
}
