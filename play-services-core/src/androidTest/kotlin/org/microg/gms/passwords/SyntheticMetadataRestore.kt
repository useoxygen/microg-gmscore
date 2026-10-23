/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants
import java.security.MessageDigest

/** Synthetic-only cleanup. A cloud update may retain an omitted encrypted notes backup. */
internal object SyntheticMetadataRestore {
    fun restore(context: Context, repository: PasswordRepository, checkpoint: AndroidVaultStore,
        chromeWrapper: Boolean = false, passwordUpdate: Boolean = false,
        officialClientExpected: ByteArray? = null, unrelatedBaseline: Map<String, ByteArray>? = null,
        originalChromeLocalData: ByteArray? = null) {
        val saved = checkpoint.locked { it.load() }
        val savedOriginal = saved.chromeLocalData["original"] ?: error("No encrypted restore checkpoint")
        val raw = if (chromeWrapper) Proto.decode(savedOriginal).one(1) else savedOriginal
        val original = PasswordData.decode(raw)
        assertTrue("Restore input is outside the approved fixture", fixture(original))
        fun assertEdit(changed: PasswordData) {
            if (officialClientExpected == null) assertOwnEdit(original, changed, passwordUpdate)
            else assertTrue("Official producer payload changed after verification; checkpoint retained", fixture(changed) &&
                changed.tag() == original.tag() && changed.password == original.password &&
                (MessageDigest.isEqual(changed.encode(), officialClientExpected) || MessageDigest.isEqual(changed.encode(), raw)))
        }
        val tag = original.tag()
        val auth = AuthManager(context, repository.account.name, Constants.GMS_PACKAGE_NAME,
            "oauth2:https://www.googleapis.com/auth/login_manager").apply {
            isGmsApp = true; setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
        }
        val token = auth.requestAuthWithBackgroundResolution(false).auth ?: throw VaultLockedException()
        val api = PasswordApi(GooglePasswordTransport({
            assertTrue("Designated account was removed", AccountManager.get(context).getAccountsByType("com.google").contains(repository.account))
            token
        }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl()))
        val readStore = AndroidVaultStore(context, "password-fixture-metadata-read/" + repository.account.name)
        assertTrue("Read fixture must have no mutations", readStore.locked { it.load().mutations.isEmpty() })
        VaultSync(api, readStore).synchronize { VaultKeys.unlock(it) }
        val remote = readStore.locked { it.load() }
        val keys = VaultKeys.unlock(remote.metadata!!)
        try {
            val current = remote.records.singleOrNull { Proto.decode(it).string(2) == tag }
            val data = current?.let { PasswordResourceCodec.read(it, keys) }
            val resuming = saved.chromeLocalData["recreate"] != null
            assertTrue("Unexpected remote deletion; checkpoint retained", data != null || resuming)
            data?.let(::assertEdit)
            val unrelated = remote.records.filter { Proto.decode(it).string(2) != tag }
                .associate { Proto.decode(it).string(2) to PasswordResourceCodec.read(it, keys).encode() }
            if (unrelatedBaseline != null) assertTrue("Unrelated data changed before restoration", unrelatedBaseline.keys == unrelated.keys &&
                unrelatedBaseline.all { (id, bytes) -> MessageDigest.isEqual(bytes, unrelated.getValue(id)) })
            val state = repository.store.locked { it.load() }
            val local = originalChromeLocalData ?: if (chromeWrapper) Proto.decode(savedOriginal).optional(3) else state.chromeLocalData[tag]
            assertTrue("Unrelated journal cannot be resolved by a fixture", state.preferenceMutations.isEmpty() &&
                state.mutations.all { it.tag == tag && (it.kind == MutationKind.UPDATE || resuming) })
            state.mutations.mapNotNull { it.desired }.forEach { assertEdit(PasswordResourceCodec.read(it, keys)) }
            // Only structural booleans leave the phone. No plaintext, hashes of passwords or account IDs.
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("passwordFixtureRestoreShape", "mainOriginal=${current?.let { entity ->
                    val decrypted = keys.decrypt(Proto.decode(entity).one(6))
                    try { MessageDigest.isEqual(raw, decrypted) } finally { decrypted.fill(0) }
                }},backupPresent=${current?.let { Proto.decode(it).optional(10) != null }}," +
                    "normalizedOriginal=${data?.let { MessageDigest.isEqual(raw, it.encode()) }}," +
                    "originalModifiedPresent=${Proto.decode(raw).integer(20) != null}," +
                    "remoteModifiedPresent=${data?.let { Proto.decode(it.encode()).integer(20) != null }}," +
                    "passwordRestored=${data?.let { it.password == original.password }}")
            })
            // The checkpoint commits the recreation intent before resolving the prior test edit.
            // Re-creation is explicitly authorized for this single synthetic fixture, and clears
            // a server-retained backup while restoring the exact pre-test payload.
            checkpoint.locked { session -> val value = session.load()
                value.chromeLocalData["recreate"] = byteArrayOf(1); session.save(value)
            }
            if (current != null) {
                repository.store.locked { session ->
                    val latest = session.load()
                    assertTrue("Unrelated operation appeared", latest.mutations.all { it.tag == tag })
                    latest.mutations.clear()
                    latest.metadata = remote.metadata
                    latest.records.clear(); latest.records.addAll(remote.records)
                    val transaction = object : VaultSession {
                        override fun load() = latest
                        override fun save(state: VaultState) = session.save(state)
                    }
                    VaultSync(api, repository.store).enqueue(transaction, MutationKind.DELETE, tag)
                }
                repository.refresh()
                repository.refresh()
                assertTrue("Synthetic delete remains uncertain; checkpoint retained", repository.store.locked {
                    it.load().mutations.isEmpty()
                } && repository.list(false).none { it.id == tag })
            } else {
                repository.refresh()
                assertTrue("Interrupted delete remains uncertain; checkpoint retained", repository.store.locked { it.load().mutations.isEmpty() })
            }
            repository.store.locked { session ->
                val latest = session.load()
                val freshKeys = VaultKeys.unlock(latest.metadata!!)
                try {
                    val transaction = object : VaultSession {
                        override fun load() = latest
                        override fun save(state: VaultState) {
                            if (local != null) state.chromeLocalData[tag] = local
                            session.save(state)
                        }
                    }
                    VaultSync(api, repository.store).enqueue(transaction, MutationKind.CREATE, tag,
                        PasswordResourceCodec.encrypt(original, freshKeys))
                }
                finally { freshKeys.destroy() }
            }
            val receipt = repository.refresh()
            repository.refresh()
            val restored = repository.list(false).single { it.id == tag }
            assertTrue("Exact synthetic restoration is unverified; checkpoint retained",
                receipt.conflicts == 0 && receipt.indeterminate == 0 && MessageDigest.isEqual(raw, restored.data.encode()) &&
                    repository.store.locked { it.load().mutations.isEmpty() })
            val after = repository.list(false).filter { it.id != tag }.associate { it.id to it.data.encode() }
            assertTrue("Unrelated data changed", unrelated.keys == after.keys &&
                unrelated.all { (id, bytes) -> MessageDigest.isEqual(bytes, after.getValue(id)) })
            checkpoint.clear(); readStore.clear()
        } finally { keys.destroy() }
    }

    private fun assertOwnEdit(original: PasswordData, changed: PasswordData, passwordUpdate: Boolean) {
        fun core(value: PasswordData) = Proto.decode(value.encode()).let {
            if (passwordUpdate) it.replace(8, null).replace(20, null) else it.replace(15, null).replace(22, null)
        }.encode()
        assertTrue("A non-metadata change requires separate reconciliation", fixture(changed) &&
            MessageDigest.isEqual(core(original), core(changed)))
        if (passwordUpdate) {
            assertTrue("Unrecognized synthetic password change", changed.password == original.password ||
                Regex("synthetic-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(changed.password))
            return
        }
        assertTrue("Unrecognized name change", changed.displayName == original.displayName ||
            changed.displayName.startsWith("cyc654-name-") || changed.displayName.startsWith("cyc654-remote-"))
        assertTrue("Unrecognized note change", changed.defaultNote?.value == original.defaultNote?.value ||
            changed.defaultNote?.value?.startsWith("cyc654-note-") == true)
        val named = original.notes.filter { it.name.isNotEmpty() }.associate { it.name to it.encode() }
        val other = changed.notes.filter { it.name.isNotEmpty() }.associate { it.name to it.encode() }
        assertTrue("Named notes changed", named.keys == other.keys && named.all { (name, bytes) -> MessageDigest.isEqual(bytes, other.getValue(name)) })
    }
    private fun fixture(data: PasswordData) = data.realm == "https://cyc651.example.invalid/" &&
        data.username == "cyc651-synthetic-user" && !data.blocked && data.password.isNotEmpty()
}
