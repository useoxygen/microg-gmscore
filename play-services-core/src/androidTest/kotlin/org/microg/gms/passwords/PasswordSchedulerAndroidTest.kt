/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Kept separate so the identical regression fixture can also exercise the earlier product APK. */
@RunWith(AndroidJUnit4::class)
class PasswordSchedulerAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    @Test fun secondaryProcessQueuesEveryFollowUpSync() {
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val accounts = AccountManager.get(context)
        val account = accounts.getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing existing pending writes", repository.store.locked {
            val state = it.load(); state.mutations.isEmpty() && state.preferenceMutations.isEmpty()
        })
        val marker = PasswordRepository.hash("google:" + accounts.getUserData(account, "GoogleUserId"))
        val workName = "password-now-$marker"
        val manager = RemoteWorkManager.getInstance(context)
        fun jobs() = manager.getWorkInfos(WorkQuery.Builder.fromUniqueWorkNames(listOf(workName)).build()).get(10, TimeUnit.SECONDS)
        val wasEnabled = repository.enabled
        try {
            repository.setEnabled(true)
            val baseline = jobs().map { it.id }.toSet()
            repository.schedule(); repository.schedule()
            val ids = jobs().map { it.id }.toSet() - baseline
            assertTrue("A follow-up sync was dropped while the unique chain existed", ids.size == 2)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            var completed = false
            while (System.nanoTime() < deadline) {
                val current = jobs().filter { it.id in ids }
                if (current.size == 2 && current.all { it.state.isFinished }) {
                    completed = current.all { it.state == WorkInfo.State.SUCCEEDED }
                    break
                }
                Thread.sleep(100)
            }
            assertTrue("Follow-up sync jobs did not both complete", completed)
        } finally { if (!wasEnabled) repository.setEnabled(false) }
    }

}
