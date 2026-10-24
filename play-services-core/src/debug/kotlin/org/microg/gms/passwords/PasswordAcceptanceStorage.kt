/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Separate debug journal root: ordinary passkey account cleanup must not erase interrupted test cleanup evidence. */
internal object PasswordAcceptanceStorage {
    fun store(context: Context, identity: String, verify: () -> Unit): AndroidPasskeyStore {
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "password-acceptance-v1").also {
                check(it.isDirectory || it.mkdirs())
            }
        }
        return AndroidPasskeyStore(isolated, "debug-acceptance/$identity", verify)
    }
}
