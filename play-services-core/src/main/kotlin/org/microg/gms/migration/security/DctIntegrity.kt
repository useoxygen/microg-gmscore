/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Uses the installed microG vending Integrity/DroidGuard path, including its opt-in policy. */
internal class DctIntegrity(private val context: Context) {
    fun token(project: Long, hash: String): String {
        require(project > 0)
        val service = CompletableFuture<IBinder>()
        val pending = java.util.concurrent.atomic.AtomicReference<CompletableFuture<Bundle>>()
        fun disconnected(reason: String) {
            val error = IllegalStateException(reason); service.completeExceptionally(error); pending.get()?.completeExceptionally(error)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) { disconnected("Integrity service disconnected") }
            override fun onBindingDied(name: ComponentName) { disconnected("Integrity binding died") }
            override fun onNullBinding(name: ComponentName) { disconnected("Integrity unavailable") }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50)
        fun <T> await(future: CompletableFuture<T>): T = future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        android.util.Log.i("GmsDct", "Integrity provider bind started")
        check(context.bindService(Intent("com.google.android.play.core.expressintegrityservice.BIND_EXPRESS_INTEGRITY_SERVICE")
            .setPackage("com.android.vending"), connection, Context.BIND_AUTO_CREATE)) { "Integrity unavailable" }
        try {
            val binder = await(service)
            fun request(code: Int, callbackCode: Int, bundle: Bundle): Bundle {
                val result = CompletableFuture<Bundle>(); pending.set(result)
                val callback = object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        if (code != callbackCode) return super.onTransact(code, data, reply, flags)
                        data.enforceInterface("com.google.android.play.core.integrity.protocol.IExpressIntegrityServiceCallback")
                        val value = if (data.readInt() == 1) Bundle.CREATOR.createFromParcel(data) else Bundle()
                        result.complete(value); reply?.writeNoException(); return true
                    }
                }
                val input = Parcel.obtain(); val output = Parcel.obtain()
                try {
                    input.writeInterfaceToken("com.google.android.play.core.integrity.protocol.IExpressIntegrityService")
                    input.writeInt(1); bundle.writeToParcel(input, 0); input.writeStrongBinder(callback)
                    check(binder.transact(code, input, output, 0)); output.readException()
                } finally { input.recycle(); output.recycle() }
                return await(result).also {
                    val error = it.getInt("error", 0)
                    android.util.Log.i("GmsDct", "Integrity provider stage=${if (code == 2) "warmup" else "token"} status=$error")
                    check(error == 0) { "Integrity provider status=$error" }
                }
            }
            fun parameters() = Bundle().apply { putString("package.name", context.packageName); putLong("cloud.prj", project) }
            android.util.Log.i("GmsDct", "Integrity provider warmup started")
            val warm = request(2, 2, parameters())
            val response = request(3, 3, parameters().apply {
                putLong("warm.up.sid", warm.getLong("warm.up.sid")); putString("nonce", hash)
            })
            return requireNotNull(response.getString("token")).also { require(it.isNotEmpty()) }
        } catch (e: java.util.concurrent.TimeoutException) {
            android.util.Log.i("GmsDct", "Integrity provider timeout; DCT status=8018"); throw e
        } catch (e: InterruptedException) {
            android.util.Log.i("GmsDct", "Integrity provider request cancelled"); Thread.currentThread().interrupt(); throw e
        } finally { pending.set(null); context.unbindService(connection) }
    }
    companion object {
        fun requestHash(salt: ByteArray, manufacturer: String, model: String): String =
            Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(
                "xdevice|${salt.toString(Charsets.UTF_8)}|$manufacturer|$model".toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
