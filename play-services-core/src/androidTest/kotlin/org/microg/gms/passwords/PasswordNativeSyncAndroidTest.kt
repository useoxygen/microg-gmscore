/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/** Opt-in live check. The ordinary instrumentation suite never selects an account or performs RPCs. */
@RunWith(AndroidJUnit4::class)
class PasswordNativeSyncAndroidTest {
    @Test fun nativeAccountRefreshAndEncryptedCacheReopen() = withFixture { repository, original ->
        val reopened = PasswordRepository(context, repository.account).list(false).single { it.id == original.id }
        assertTrue("Native cache changed the fixture", same(original.chromeBytes, reopened.chromeBytes))
    }

    @Test fun nativeUpdateAndRestoreApprovedSyntheticLogin() {
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        withFixture { repository, original ->
            // Persist the restore input before launching any effect. It contains synthetic data only,
            // remains encrypted on the phone, and survives an interrupted instrumentation process.
            val checkpoint = AndroidVaultStore(context, "password-fixture-restore/" + repository.account.name)
            val saved = checkpoint.locked { it.load().chromeLocalData["original"] }
            val restore = saved ?: original.chromeBytes.also { bytes ->
                checkpoint.locked { session -> session.save(VaultState(chromeLocalData = hashMapOf("original" to bytes))) }
            }
            val data = PasswordData.decode(Proto.decode(restore).one(1))
            assertTrue("Restore input is outside the approved fixture", isFixture(data) && data.tag() == original.id)
            try {
                val changed = data.withPassword("synthetic-" + UUID.randomUUID(), System.currentTimeMillis())
                repository.upsert(Proto.field(1, changed.encode()))
                val receipt = repository.refresh()
                assertTrue("Mutation needs owner reconciliation", receipt.conflicts == 0 && receipt.indeterminate == 0)
                val readback = PasswordRepository(context, repository.account).list(false).single { it.id == original.id }
                assertTrue("Native update did not read back", same(changed.encode(), readback.data.encode()))
            } finally {
                repository.upsert(restore)
                val receipt = repository.refresh()
                val restored = repository.list(false).single { it.id == data.tag() }
                assertTrue("Synthetic fixture restoration is unverified", receipt.conflicts == 0 && receipt.indeterminate == 0 &&
                    same(data.encode(), restored.data.encode()))
                checkpoint.clear()
            }
        }
    }

    @Test fun nativeDeleteRecreateAndRestoreTheSingleApprovedSyntheticLogin() {
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        withFixture { repository, original ->
            val checkpoint = AndroidVaultStore(context, "password-fixture-recreate/" + repository.account.name)
            val saved = checkpoint.locked { it.load().chromeLocalData["original"] }
            val restore = saved ?: original.chromeBytes.also { bytes ->
                checkpoint.locked { session -> session.save(VaultState(chromeLocalData = hashMapOf("original" to bytes))) }
            }
            val data = PasswordData.decode(Proto.decode(restore).one(1))
            assertTrue("Restore input is outside the approved fixture", isFixture(data) && data.tag() == original.id)
            try {
                repository.delete(original.chromeBytes)
                val deleted = repository.refresh()
                assertTrue("Delete needs reconciliation", deleted.conflicts == 0 && deleted.indeterminate == 0)
                repository.refresh()
                assertTrue("Synthetic delete was not visible remotely", repository.list(false).none { it.id == original.id })
            } finally {
                // Reconcile a lost response before deciding whether re-creation is necessary.
                repository.refresh()
                assertTrue("Uncertain delete retained; encrypted restoration input preserved",
                    repository.store.locked { it.load().mutations.isEmpty() })
                val current = repository.list(false).singleOrNull { it.id == original.id }
                if (current == null || !same(current.data.encode(), data.encode())) {
                    repository.upsert(restore)
                    val recreated = repository.refresh()
                    assertTrue("Re-creation needs reconciliation", recreated.conflicts == 0 && recreated.indeterminate == 0)
                }
                repository.refresh()
                val restored = repository.list(false).single { it.id == original.id }
                assertTrue("Exact synthetic payload restoration is unverified", same(data.encode(), restored.data.encode()))
                checkpoint.clear()
            }
        }
    }

    private fun withFixture(block: (PasswordRepository, PasswordView) -> Unit) {
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing an existing mutation journal", repository.store.locked { it.load().mutations.isEmpty() })
        val wasEnabled = repository.enabled
        try {
            repository.setEnabled(true)
            repository.refresh()
            val entries = repository.list(false)
            val original = entries.single { isFixture(it.data) }
            assertTrue("Fixture has no password", original.data.password.isNotEmpty())
            // Compare decrypted bytes, never emit passwords, realms or other account records on failure.
            val untouched = entries.filter { it.id != original.id }.associate { it.id to it.data.encode() }
            try { block(repository, original) } finally {
                repository.refresh()
                val after = repository.list(false).filter { it.id != original.id }.associate { it.id to it.data.encode() }
                assertTrue("An unrelated record changed", untouched.keys == after.keys && untouched.all { (id, data) -> same(data, after.getValue(id)) })
            }
        } finally { if (!wasEnabled) repository.setEnabled(false) }
    }

    private fun isFixture(data: PasswordData) = data.realm == "https://cyc651.example.invalid/" &&
        data.username == "cyc651-synthetic-user" && !data.blocked
    private fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
}
