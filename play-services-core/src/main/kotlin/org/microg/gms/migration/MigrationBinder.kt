/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader

/** A migration session's binder cannot be passed to another uid. No request data is logged. */
internal abstract class MigrationBinder(private val descriptor: String, private val ownerUid: Int?) : Binder() {
    init { attachInterface(null, descriptor) }

    final override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code > 0xffffff) return super.onTransact(code, data, reply, flags)
        check(ownerUid == null || Binder.getCallingUid() == ownerUid) { "Migration binder belongs to another uid" }
        data.enforceInterface(descriptor)
        return dispatch(code, data, reply)
    }

    protected abstract fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean
}

internal fun Parcel.requireEnd() { require(dataAvail() == 0) { "Unexpected migration request fields" } }

/** Skip only a nullable SafeParcelable, validating every field boundary. Never deserialize arbitrary classes. */
internal fun Parcel.skipSafeParcelable() {
    if (readInt() == 0) return
    val end = SafeParcelReader.readObjectHeader(this)
    while (dataPosition() < end) {
        SafeParcelReader.skip(this, SafeParcelReader.readHeader(this))
        require(dataPosition() <= end) { "Migration field exceeds its object" }
    }
    require(dataPosition() == end)
}

internal fun Parcel.writeNullable(value: Parcelable?) {
    writeInt(if (value == null) 0 else 1)
    value?.writeToParcel(this, 0)
}

internal fun sendCallback(callback: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit = {}) {
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(descriptor)
        parcel.write()
        check(callback.transact(code, parcel, null, IBinder.FLAG_ONEWAY)) { "Unsupported migration callback" }
    } finally { parcel.recycle() }
}
