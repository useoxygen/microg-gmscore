/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.content.ContentValues
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.data.DataHolder
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.microg.gms.BaseService
import org.microg.gms.utils.ExtendedPackageInfo
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils

/** Independently implemented Chrome first-party wire contract (GMS 26.11, service 303). */
class ChromePasswordService : BaseService("ChromePasswordService", GmsService.CHROME_SYNC_FIRST_PARTY) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val caller = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
        if (android.os.Build.VERSION.SDK_INT < 26 || caller == null ||
            !ExtendedPackageInfo(this, caller).hasGooglePackagePermission(GooglePackagePermission.CREDENTIALS)) {
            callback.onPostInitComplete(ConnectionResult.API_DISABLED_FOR_CONNECTION, null, null)
            return
        }
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS,
            ChromeBinder(Binder.getCallingUid(), request.account), ConnectionInfo().apply {
                features = arrayOf(Feature("FIRSTPARTY_CHROME", 1),
                    Feature("FIRSTPARTY_SET_CUSTOM_PASSPHRASE_DECRYPTION_KEY_MATERIAL", 1))
            })
    }

    private inner class ChromeBinder(private val uid: Int, private val account: Account?) : Binder() {
        init { attachInterface(null, DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code !in 2..9) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != uid) throw SecurityException("Credential connection owner changed")
            data.enforceInterface(DESCRIPTOR)
            val callback = data.readStrongBinder() ?: throw IllegalArgumentException("Missing callback")
            var options: ChromeListOptions? = null
            var entity: ByteArray? = null
            var facet: String? = null
            var encryption: ByteArray? = null
            var mac: ByteArray? = null
            var preferenceName: String? = null
            var preferenceValue: String? = null
            when (code) {
                2 -> options = ChromeListOptions.read(data)
                3, 4 -> entity = readNullable(data, Bundle.CREATOR)?.getByteArray("entity")
                    ?.takeIf { it.size <= 512 * 1024 } ?: throw IllegalArgumentException("Invalid password entity")
                5 -> preferenceName = data.readString()
                6 -> { preferenceName = data.readString(); preferenceValue = data.readString() }
                7 -> facet = readFacet(data)
                9 -> { encryption = data.createByteArray(); mac = data.createByteArray() }
            }
            readNullable(data, ApiMetadata.CREATOR)
            if (data.dataAvail() != 0) throw IllegalArgumentException("Trailing password request data")
            if (code in 5..6 && (preferenceName.isNullOrEmpty() || preferenceName.length > 1024 ||
                    code == 6 && (preferenceValue == null || preferenceValue.toByteArray(Charsets.UTF_8).size > 64 * 1024))) throw ProtocolException()
            val selected = options
            val payload = entity
            val requestedFacet = facet
            val encKey = encryption
            val macKey = mac
            val settingName = preferenceName
            val settingValue = preferenceValue
            lifecycleScope.launch(Dispatchers.IO) {
                var status = Status.SUCCESS
                var values: List<ByteArray>? = null
                var setting: String? = null
                try {
                    val repository: PasswordVault = account?.let { PasswordRepository(this@ChromePasswordService, it) }
                        ?: DevicePasswordVault(this@ChromePasswordService)
                    when (code) {
                        2 -> values = repository.list(true).filter { selected!!.accepts(it.data) }.map { it.chromeBytes }
                        3 -> repository.upsert(payload!!)
                        4 -> repository.delete(payload!!)
                        5 -> setting = repository.getPreference(settingName!!)
                        6 -> repository.setPreference(settingName!!, settingValue!!)
                        7 -> values = repository.list(true).filter { it.data.realm == requestedFacet }.map { it.chromeBytes }
                        // This response has a distinct UI-info wrapper. Do not advertise affiliation enrichment yet.
                        8 -> values = repository.list(true).map { Proto.field(1, it.chromeBytes) }
                        9 -> repository.setCustomKey(encKey ?: throw ProtocolException(), macKey ?: throw ProtocolException())
                    }
                } catch (_: VaultLockedException) { status = Status(4) }
                catch (_: ConflictException) { status = Status(13) }
                catch (_: PendingMutationException) { status = Status(13) }
                catch (_: Exception) { status = Status.INTERNAL_ERROR }
                finally { payload?.fill(0); encKey?.fill(0); macKey?.fill(0) }
                try {
                    when (code) {
                        2 -> sendRows(callback, LIST_CALLBACK, status, values, listInfo = true)
                        7, 8 -> sendRows(callback, DATA_CALLBACK, status, values, listInfo = false)
                        5 -> send(callback, SETTINGS_CALLBACK) {
                            nullable(status); writeInt(if (setting != null) 1 else 0); writeString(setting); nullable(ApiMetadata.DEFAULT)
                        }
                        else -> send(callback, STATUS_CALLBACK) { nullable(status) }
                    }
                } catch (_: Exception) { /* The durable outbox survives a client disconnect. */ }
                finally { values?.forEach { it.fill(0) } }
            }
            reply?.writeNoException()
            return true
        }
    }

    companion object {
        const val DESCRIPTOR = "com.google.android.gms.chromesync.firstparty.internal.IChromeSyncApiService"
        private const val LIST_CALLBACK = "com.google.android.gms.chromesync.firstparty.internal.IListPasswordsResultCallback"
        private const val DATA_CALLBACK = "com.google.android.gms.chromesync.common.internal.IDataHolderResultCallback"
        private const val SETTINGS_CALLBACK = "com.google.android.gms.chromesync.firstparty.internal.ISettingsCallback"
        private const val STATUS_CALLBACK = "com.google.android.gms.common.api.internal.IStatusCallback"
        private fun sendRows(callback: IBinder, descriptor: String, status: Status, values: List<ByteArray>?, listInfo: Boolean) {
            val rows = values?.let { entries -> DataHolder.builder(arrayOf("value")).apply {
                entries.forEach { value -> withRow(ContentValues().apply { put("value", value) }) }
            }.build(0) }
            try { send(callback, descriptor) {
                nullable(status)
                if (listInfo) writeInt(0) // Google sends a nullable, empty PasswordListInfo.
                nullable(rows); nullable(ApiMetadata.DEFAULT)
            } } finally { rows?.close() }
        }
        private fun send(callback: IBinder, descriptor: String, write: Parcel.() -> Unit) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(descriptor); parcel.write()
                if (!callback.transact(1, parcel, null, IBinder.FLAG_ONEWAY)) throw IllegalStateException("Credential callback unavailable")
            } finally { parcel.recycle() }
        }
        private fun Parcel.nullable(value: Parcelable?) {
            writeInt(if (value == null) 0 else 1); value?.writeToParcel(this, 0)
        }
        private fun <T> readNullable(parcel: Parcel, creator: Parcelable.Creator<T>): T? =
            if (parcel.readInt() == 0) null else creator.createFromParcel(parcel)
        private fun readFacet(parcel: Parcel): String {
            if (parcel.readInt() == 0) throw ProtocolException()
            val end = SafeParcelReader.readObjectHeader(parcel)
            var facet: String? = null
            while (parcel.dataPosition() < end) {
                val header = SafeParcelReader.readHeader(parcel)
                if (SafeParcelReader.getFieldId(header) == 1) facet = SafeParcelReader.readString(parcel, header)
                else SafeParcelReader.skip(parcel, header)
            }
            if (parcel.dataPosition() != end || facet.isNullOrEmpty()) throw ProtocolException()
            return facet
        }
    }
}

private class ChromeListOptions(val query: String?, val filter: Int, val type: Int, val from: Long?, val until: Long?) {
    fun accepts(data: PasswordData): Boolean {
        return PasswordFilter(query, filter, type, from, until).accepts(data)
    }
    companion object {
        fun read(parcel: Parcel): ChromeListOptions {
            if (parcel.readInt() == 0) throw ProtocolException()
            val end = SafeParcelReader.readObjectHeader(parcel)
            var query: String? = null
            var filter = 0; var type = 0
            var from: Long? = null; var until: Long? = null
            while (parcel.dataPosition() < end) {
                val header = SafeParcelReader.readHeader(parcel)
                when (SafeParcelReader.getFieldId(header)) {
                    1 -> query = SafeParcelReader.readString(parcel, header)
                    2 -> filter = SafeParcelReader.readInt(parcel, header)
                    3 -> type = SafeParcelReader.readInt(parcel, header)
                    4, 5 -> {
                        val size = if (header ushr 16 == 65535) parcel.readInt() else header ushr 16
                        val date = when (size) { 0 -> null; 8 -> parcel.readLong(); else -> throw ProtocolException() }
                        if (SafeParcelReader.getFieldId(header) == 4) from = date else until = date
                    }
                    else -> SafeParcelReader.skip(parcel, header)
                }
                if (parcel.dataPosition() > end) throw ProtocolException()
            }
            if (parcel.dataPosition() != end || (query?.length ?: 0) > 4096) throw ProtocolException()
            return ChromeListOptions(query, filter, type, from, until)
        }
    }
}
