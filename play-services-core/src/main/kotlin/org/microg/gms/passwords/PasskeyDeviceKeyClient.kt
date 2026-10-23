/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.util.Base64
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.tasks.Tasks
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Genuine native DroidGuard bytes and exact server-returned key versions; failure has no local substitute. */
internal class PasskeyDeviceKeyClient(private val context: Context, private val transport: PasswordTransport,
    private val verify: () -> Unit) {
    fun get(required: Set<Int>, available: Boolean, rapt: String? = null): List<PasskeyDomainKey> {
        if (required.any { it <= 0 }) throw ProtocolException()
        verify()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val encoded = Tasks.await(DroidGuardClient.getResults(context, PasskeyDeviceAuthorization.FLOW,
            PasskeyDeviceAuthorization.signals(salt, available, rapt)), 30, TimeUnit.SECONDS)
        if (encoded.length > 2 * 1024 * 1024) throw ProtocolException()
        val signals = try { Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
            catch (_: Exception) { throw ProtocolException() }
        try {
            if (signals.isEmpty() || signals.take(8).toByteArray().contentEquals("ERROR : ".toByteArray(Charsets.US_ASCII)))
                throw VaultLockedException()
            verify()
            val request = PasskeyDeviceAuthorization.request(salt, available, signals, rapt)
            val response = try { transport.invoke(PasswordMethod.GET_DEVICE_AUTHORIZATION, request) } finally { request.fill(0) }
            try { verify(); return PasskeyDeviceAuthorization.response(response, required) } finally { response.fill(0) }
        } finally { salt.fill(0); signals.fill(0) }
    }
}
