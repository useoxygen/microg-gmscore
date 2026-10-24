/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real AEAD/FileLock boundary; no Google account, network, device recovery, or provider action. */
@RunWith(AndroidJUnit4::class)
class PasswordEncryptionFenceAndroidTest {
    @Test fun persistedOpenedFenceBlocksStaleReplacementEnableAndActualSyncProducers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "encryption-fence-fixture/" + UUID.randomUUID()
        var activeGaia = "1234"
        val authority = { if (activeGaia != "1234") throw VaultLockedException() }
        val owner = AndroidVaultStore(context, identity, authority)
        val ordinary = AndroidVaultStore(context, identity, authority) { state ->
            if (PasswordEncryptionState.fences(state.passwordEncryptionFlow)) throw PendingMutationException()
        }
        val metadata = Proto.field(1, "users/me/birthdays/birthday") + Proto.field(3, Proto.integer(2, 1))
        try {
            val stale = ordinary.locked { it.load() }
            PasswordEncryptionSnapshot("1234", "users/me/birthdays/birthday", metadata, emptyList(), emptyList(), emptyList()).use { before ->
                PasswordEncryptionState.prepare("fixture", PasswordEncryptionOperation.RESET_PASSKEYS, before, 10).use { ready ->
                    ready.open().use { opened -> owner.locked { session ->
                        val state = session.load(); state.passwordEncryptionFlow = opened.encode(); session.save(state)
                    } }
                }
            }
            assertThrows(PendingMutationException::class.java) { ordinary.locked { it.save(stale) } }
            assertThrows(PendingMutationException::class.java) { ordinary.setEnabled(false) }
            assertThrows(PendingMutationException::class.java) { ordinary.registerAccount() }
            assertThrows(PendingMutationException::class.java) { ordinary.clear() }
            val calls = AtomicInteger()
            val api = PasswordApi(PasswordTransport { _, _ -> calls.incrementAndGet(); throw AssertionError("Fenced producer reached RPC") })
            assertThrows(PendingMutationException::class.java) { VaultSync(api, ordinary).synchronize { throw AssertionError("Fenced producer opened keys") } }
            assertThrows(PendingMutationException::class.java) { PreferenceSync(api, ordinary).enqueue("fixture", "true") }
            assertEquals(0, calls.get())
            assertTrue(owner.locked { PasswordEncryptionState.fences(it.load().passwordEncryptionFlow) })
            // Removing and re-adding the same account label cannot open the protected journal.
            activeGaia = "5678"
            assertThrows(VaultLockedException::class.java) { owner.locked { it.load() } }
        } finally { activeGaia = "1234"; owner.clear() }
    }
}
