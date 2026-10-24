/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.telephony.euicc.EuiccManager
import androidx.lifecycle.LifecycleService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.migration.MigrationBinder
import org.microg.gms.migration.requireEnd
import org.microg.gms.migration.sendCallback
import org.microg.gms.migration.writeNullable
import java.util.EnumSet
import java.util.concurrent.Executors

/** Exact GMS333 target broker. Carrier authorization and LPA results remain authoritative. */
class EsimTransferService : LifecycleService() {
    private val binders = mutableMapOf<Int, EsimTransferBinder>()
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ESIM, GmsService.ESIM_PROVISIONING)) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            PackageUtils.getAndCheckCallingPackage(this@EsimTransferService, request.packageName)
            if (service == GmsService.ESIM_PROVISIONING) {
                // Pinned GMS508 is a separately feature-gated pipeline. We do not enable its stub discovery.
                callback.onPostInitComplete(ConnectionResult.API_UNAVAILABLE, null, null); return
            }
            val uid = Binder.getCallingUid()
            val binder = synchronized(binders) { binders.getOrPut(uid) { EsimTransferBinder(this@EsimTransferService, uid) } }
            callback.onPostInitComplete(ConnectionResult.SUCCESS, binder, null)
        }
    }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
    override fun onDestroy() { synchronized(binders) { binders.values.forEach { it.close() }; binders.clear() }; super.onDestroy() }
}

internal class EsimTransferBinder(private val context: Context, private val uid: Int,
    private val engine: EsimTransferEngine = EsimTransferEngine(context, uid),
    private val permission: (Set<String>) -> Unit = { required ->
        required.forEach { name ->
            if (context.checkPermission(name, Binder.getCallingPid(), Binder.getCallingUid()) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Missing $name")
        }
    }, private val worker: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()) : MigrationBinder(DESCRIPTOR, uid), java.io.Closeable {
    private fun record(data: Parcel): EsimWireRecord {
        require(data.readInt() == 1); return EsimWireRecord.CREATOR.createFromParcel(data)
    }
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun releaseOperation() {
        runCatching { engine.cancel() }
        runCatching { engine.close() }
    }
    /** A dead/failed remote callback must never reach a thread's uncaught-exception handler. */
    private fun execute(record: EsimWireRecord? = null, action: () -> Unit) {
        try {
            worker.execute {
                try { action() }
                catch (_: Exception) { releaseOperation() }
                finally { record?.close() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { record?.close() }
    }
    private fun perform(callback: IBinder, callbackName: String, record: EsimWireRecord? = null,
        value: Boolean = false, action: () -> Parcelable? = { null }) {
        execute(record) {
            var result: Parcelable? = null
            val status = try { result = action(); Status.SUCCESS }
                catch (e: EsimFailure) { Status(e.status) } catch (_: Exception) { Status(8) }
            try { sendCallback(callback, PREFIX + callbackName, 1) { writeNullable(status); if (value) writeNullable(result) } }
            finally { (result as? EsimWireRecord)?.close() }
        }
    }
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        // GMS checks the real calling UID's permission, not merely the broker's package string.
        permission(buildSet {
            // Pinned ccww: preparation5 is READ-only; starts6/10/20 require WRITE, not READ.
            if (code in setOf(1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 15, 17, 19, 20, 21, 23))
                add("android.permission.WRITE_EMBEDDED_SUBSCRIPTIONS")
            if (code == 1 || code == 5) add("android.permission.READ_PRIVILEGED_PHONE_STATE")
            //14/16 and disabled-feature18/22 have no extra permission check in the pin.
        })
        when (code) {
            1 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                perform(callback, "IRetrieveProfilesCallbacks", value = true) { engine.retrieveProfiles() }
            }
            2 -> {
                val callback = requireNotNull(data.readStrongBinder()); val profile = record(data); data.requireEnd()
                perform(callback, "ITransferCredentialsCallbacks", profile, true) { engine.credential(profile) }
            }
            3 -> {
                val callback = requireNotNull(data.readStrongBinder()); val subscription = data.readInt(); val profile = record(data); data.requireEnd()
                perform(callback, "IActivateServiceCallbacks", profile) { engine.activate(subscription, profile); null }
            }
            4 -> {
                val callback = requireNotNull(data.readStrongBinder()); val request = record(data); data.requireEnd()
                perform(callback, "ISyncProfileCredentialCallbacks", request) { throw EsimFailure(17) }
            }
            13, 17, 23 -> {
                val callback = requireNotNull(data.readStrongBinder())
                // SubscriptionInfo in13; conversion SafeParcelable in17/23. No conversion is advertised.
                if (code == 13) { if (data.readInt() != 0) android.telephony.SubscriptionInfo.CREATOR.createFromParcel(data) }
                else if (data.readInt() != 0) EsimWireRecord.CREATOR.createFromParcel(data).close()
                data.requireEnd()
                perform(callback, "IFetchConversionCredentialCallbacks", value = true) { throw EsimFailure(17) }
                if (code == 23) { reply?.writeNoException(); reply?.writeStrongBinder(signal(false)); return true }
            }
            18 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                perform(callback, "IRetrieveCloudProfilesCallbacks", value = true) { throw EsimFailure(17) }
            }
            15 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.readStrongBinder(); data.requireEnd()
                perform(callback, "IRetrievePendingTransferCredentialsCallbacks") { throw EsimFailure(17) }
            }
            22 -> {
                val callback = requireNotNull(data.readStrongBinder())
                val count = data.readInt(); require(count in -1..32)
                repeat(count.coerceAtLeast(0)) { if (data.readInt() != 0) EsimWireRecord.CREATOR.createFromParcel(data).close() }
                val verified = requireNotNull(data.readStrongBinder()); data.requireEnd()
                // Matches the pinned feature-disabled branch; multi-profile transfer is not enabled.
                sendCallback(callback, "com.google.android.gms.common.api.internal.IStatusCallback", 1) { writeNullable(Status(48585)) }
                sendCallback(verified, PREFIX + "IVerifiedTransferListCallbacks", 1) { writeInt(0) }
                reply?.writeNoException(); reply?.writeStrongBinder(signal(false)); return true
            }
            5 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                // Target-only implementation cannot prepare/export the source's profiles.
                perform(callback, "IPrepareEsimTransferDataCallbacks") { throw EsimFailure(17) }
            }
            6, 10, 20 -> {
                val callback = requireNotNull(data.readStrongBinder()); val request = record(data)
                if (code != 6) data.readStrongBinder() // IStartTransferSessionListener; no invented progress events.
                data.requireEnd()
                perform(callback, "IStartTransferSessionCallbacks", request) { engine.start(request); null }
                if (code != 6) {
                    reply?.writeNoException(); reply?.writeStrongBinder(signal(code == 10)); return true
                }
            }
            7 -> {
                val callback = requireNotNull(data.readStrongBinder()); val request = record(data); data.requireEnd()
                perform(callback, "IEndTransferSessionCallbacks", request) { engine.end(); null }
            }
            8 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                // Legacy QR uses a Bluetooth-MAC/AES-key deep link, not a fabricated six-digit DCT URI.
                sendCallback(callback, PREFIX + "IGetQrCodeDeepLinkUrlCallbacks", 1) { writeNullable(Status(17)); writeString(null) }
            }
            14 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.readLong(); data.requireEnd()
                sendCallback(callback, PREFIX + "IWaitForWifiOnSourceDeviceCallbacks", 1) { writeNullable(Status(17)); writeInt(0) }
            }
            9, 11, 21 -> {
                val callback = requireNotNull(data.readStrongBinder()); val profile = record(data)
                val verified = requireNotNull(data.readStrongBinder()); data.requireEnd()
                execute(profile) {
                    sendCallback(verified, PREFIX + "IVerifiedTransferCredentialCallbacks", 1) { writeInt(0) }
                    sendCallback(callback, "com.google.android.gms.common.api.internal.IStatusCallback", 1) { writeNullable(Status(48503)) }
                }
                if (code != 9) { reply?.writeNoException(); reply?.writeStrongBinder(signal(code == 11)); return true }
            }
            12, 19 -> {
                val callback = requireNotNull(data.readStrongBinder()); val sessionType = data.readInt()
                val connector = if (code == 19) data.readInt() else 0; data.requireEnd()
                val enabled = context.getSystemService(EuiccManager::class.java)?.isEnabled == true
                sendCallback(callback, PREFIX + "IIsEsimTransferAvailableCallbacks", 1) {
                    writeNullable(Status.SUCCESS); writeInt(if (enabled && connector in 0..1 && sessionType in 0..3) 1 else 0)
                }
            }
            16 -> {
                val callback = requireNotNull(data.readStrongBinder()); val profile = record(data); val status = data.readInt(); data.requireEnd()
                perform(callback, "INotifyDownloadStatusCallbacks", profile) { engine.notifyDownload(profile, status); null }
            }
            else -> return false
        }
        reply?.writeNoException(); return true
    }
    private fun signal(cancellationOnly: Boolean): IBinder = object : MigrationBinder(
        PREFIX + if (cancellationOnly) "IEsimTransferCancellationSignal" else "IEsimTransferSignal", uid) {
        override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
            data.requireEnd()
            if (code != 1 && (cancellationOnly || code != 2)) return false
            if (code == 1) engine.cancel()
            // Code2 only reports status-change notification; no fabricated verification/carrier result.
            return true // Google signal is oneway and writes no reply fields.
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { engine.cancel() }
        execute { releaseOperation() }
        worker.shutdown()
    }
    companion object { const val DESCRIPTOR = "com.google.android.gms.esim.internal.IEsimTransferService"; const val PREFIX = "com.google.android.gms.esim.internal." }
}
