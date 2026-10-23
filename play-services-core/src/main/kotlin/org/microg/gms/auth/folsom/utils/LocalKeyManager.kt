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
import java.util.concurrent.ConcurrentHashMap
import org.microg.gms.passwords.EnrollmentState
import org.microg.gms.passwords.EnrollmentPhase
import org.microg.gms.passwords.EnrollmentWire

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
    private fun keySnapshot(accountName: String, domainId: String, validateLegacyKeys: ((List<Keys>) -> Boolean)? = null,
        expectedUserId: String? = null): FolsomKeySnapshot {
        val obfuscatedId = obfuscateAccountName(accountName)
        val userId = stableAccountId(accountName)
        if (expectedUserId != null && userId != expectedUserId) throw TinkEncryptionException("Recovery account changed")
        var result = FolsomKeySnapshot(0, emptyList())
        updateStore { store ->
            val account = store.findAccount(obfuscatedId) ?: return@updateStore store
            val domain = account.findDomain(domainId) ?: return@updateStore store
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
        val lockName = obfuscateAccountName(expectedUserId)
        val mutex = enrollmentLocks.getOrPut(lockName) { Any() }
        return synchronized(mutex) {
            RandomAccessFile(File(context.noBackupFilesDir, "folsom-enroll-$lockName.lock"), "rw").use { lock ->
                lock.channel.lock().use {
                    if (stableAccountId(accountName) != expectedUserId) throw TinkEncryptionException("Recovery account changed")
                    val session = ChromesyncEnrollmentSession(accountName, expectedUserId)
                    try { action(session) } finally { session.destroy() }
                }
            }
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
                    val account = checked(store.findAccount(obfuscatedId))
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
        private val enrollmentLocks = ConcurrentHashMap<String, Any>()
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LocalKeyManager? = null
        fun getInstance(context: Context): LocalKeyManager = instance ?: synchronized(this) {
            instance ?: LocalKeyManager(context.applicationContext).also { instance = it }
        }
    }
}
