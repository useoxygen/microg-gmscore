package org.microg.gms.cyclon

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core-only, foreground, puzzle-only bridge. No sign-in, arbitrary scripts, URLs or Register
 * action.
 */
class EnrollmentChallengeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        ctx.enforceCallingPermission(PERMISSION, "Core enrollment bridge only")
        check(
            ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.toList() ==
                listOf("ai.cyclon.core")
        )
        require(method in setOf("observe", "act", "stop") && arg == null)
        val active = AtomicBoolean(true)
        val latch = CountDownLatch(1)
        var result = Bundle().apply { putString("state", "{\"phase\":\"inactive\"}") }
        Handler(Looper.getMainLooper()).post {
            if (!active.get()) {
                latch.countDown()
                return@post
            }
            val activity = current.get()
            if (activity == null) {
                latch.countDown()
                return@post
            }
            activity.challengeRequest(method, extras, active) { reply ->
                result = reply
                latch.countDown()
            }
        }
        if (!latch.await(4, TimeUnit.SECONDS)) {
            active.set(false)
            return Bundle().apply {
                putString(
                    "state",
                    if (method == "act") "{\"phase\":\"unknown\"}"
                    else "{\"phase\":\"unavailable\"}",
                )
            }
        }
        active.set(false)
        return result
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?,
    ) = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) =
        throw UnsupportedOperationException()

    companion object {
        const val PERMISSION = "ai.cyclon.microg.permission.ENROLLMENT_CHALLENGE"
        @Volatile internal var current = WeakReference<EnrollmentActivity>(null)
    }
}
