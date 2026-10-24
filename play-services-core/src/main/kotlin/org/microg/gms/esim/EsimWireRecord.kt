/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.os.Parcel
import android.os.Parcelable
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader as Reader
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import java.io.Closeable

/** Lossless, bounded SafeParcelable storage: retain unknown carrier fields without loading peer classes.
 * eSIM records contain primitives/nested safe records, never file descriptors. No secrets in toString. */
internal class EsimWireRecord private constructor(private val bytes: ByteArray) : Parcelable, Closeable {
    private fun <T> field(id: Int, absent: T, read: (Parcel, Int) -> T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
            val end = Reader.readObjectHeader(parcel)
            while (parcel.dataPosition() < end) {
                val header = Reader.readHeader(parcel)
                if (Reader.getFieldId(header) == id) return read(parcel, header)
                Reader.skip(parcel, header)
            }
            absent
        } finally { parcel.recycle() }
    }
    fun text(id: Int): String? = field(id, null as String?) { p, h -> Reader.readString(p, h) }?.also { require(it.length <= 65536) }
    fun int(id: Int, default: Int = 0): Int = field(id, default) { p, h -> Reader.readInt(p, h) }
    fun long(id: Int, default: Long = 0): Long = field(id, default) { p, h -> Reader.readLong(p, h) }
    fun bool(id: Int): Boolean = field(id, false) { p, h -> Reader.readBool(p, h) }
    fun list(id: Int): List<EsimWireRecord> = field(id, emptyList<EsimWireRecord>()) { p, h ->
        val original = p.dataPosition()
        val size = if (h ushr 16 == 0xffff) p.readInt() else h ushr 16
        if (size != 0) {
            require(size >= 4)
            val count = p.readInt()
            // Bound allocation before Parcel.createTypedArrayList sees peer-controlled count.
            require(count in -1..1024 && count.coerceAtLeast(0) <= (size - 4) / 4)
        }
        p.setDataPosition(original)
        Reader.readParcelableList(p, h, CREATOR) ?: emptyList()
    }
    fun obj(id: Int): EsimWireRecord? = field(id, null as EsimWireRecord?) { p, h -> Reader.readParcelable(p, h, CREATOR) }
    fun replace(fields: Map<Int, (Parcel) -> Unit>): EsimWireRecord {
        val source = Parcel.obtain()
        try {
            source.unmarshall(bytes, 0, bytes.size); source.setDataPosition(0)
            return build { output ->
                val end = Reader.readObjectHeader(source)
                while (source.dataPosition() < end) {
                    val start = source.dataPosition(); val header = Reader.readHeader(source)
                    Reader.skip(source, header)
                    if (Reader.getFieldId(header) !in fields) output.appendFrom(source, start, source.dataPosition() - start)
                }
                fields.values.forEach { it(output) }
            }
        } finally { source.recycle() }
    }
    override fun describeContents() = 0
    override fun writeToParcel(destination: Parcel, flags: Int) {
        val parcel = Parcel.obtain()
        try { parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0); destination.appendFrom(parcel, 0, parcel.dataSize()) }
        finally { parcel.recycle() }
    }
    override fun toString() = "EsimWireRecord[redacted]"
    override fun close() { bytes.fill(0) }
    companion object {
        val CREATOR = object : Parcelable.Creator<EsimWireRecord> {
            override fun createFromParcel(source: Parcel): EsimWireRecord {
                val start = source.dataPosition(); val end = Reader.readObjectHeader(source)
                require(end >= start && end <= source.dataSize() && end - start <= 256 * 1024)
                while (source.dataPosition() < end) {
                    val before = source.dataPosition(); Reader.skip(source, Reader.readHeader(source))
                    require(source.dataPosition() >= before + 4 && source.dataPosition() <= end)
                }
                require(source.dataPosition() == end)
                val copy = Parcel.obtain()
                return try {
                    copy.appendFrom(source, start, end - start); require(!copy.hasFileDescriptors())
                    EsimWireRecord(copy.marshall())
                } finally { copy.recycle() }
            }
            override fun newArray(size: Int) = arrayOfNulls<EsimWireRecord>(size)
        }
        fun build(write: (Parcel) -> Unit): EsimWireRecord {
            val parcel = Parcel.obtain()
            return try { val start = Writer.writeObjectHeader(parcel); write(parcel); Writer.finishObjectHeader(parcel, start)
                parcel.setDataPosition(0); CREATOR.createFromParcel(parcel) }
            finally { parcel.recycle() }
        }
    }
}

internal class EsimProfile(val wire: EsimWireRecord) : Closeable {
    val metadata = requireNotNull(wire.obj(3)) { "Missing profile metadata" }
    val profile = requireNotNull(wire.obj(2)) { "Missing profile identity" }
    val device = wire.obj(1)
    val id = requireNotNull(wire.text(4)).also { require(it.length in 1..128) }
    val iccid = requireNotNull(profile.text(1)).also { require(it.matches(Regex("[0-9]{10,32}"))) }
    val transferType = metadata.int(1)
    val carrierId = profile.int(7)
    val entitlementUrl = metadata.text(5)
    val token = metadata.text(8)
    val tokenExpiry = metadata.long(9)
    val activationCode = metadata.text(2)
    val activationExpiry = metadata.long(6)
    override fun toString() = "EsimProfile[redacted]"
    override fun close() { metadata.close(); profile.close(); device?.close(); wire.close() }
}
