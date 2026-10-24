/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.nearby

import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import java.io.Closeable

/** Small, bounded SafeParcel vocabulary. Never instantiates a caller-selected class. */
internal class DctRecord(val schema: Map<Int, Kind>, val fields: Map<Int, Any> = emptyMap()) : Parcelable, Closeable {
    sealed class Kind {
        object IntValue : Kind(); object LongValue : Kind(); object Bool : Kind(); object Text : Kind()
        object Bytes : Kind(); object Binder : Kind(); object Ints : Kind(); object Strings : Kind()
        object UriValue : Kind(); object Fd : Kind(); object ByteArrays : Kind()
        class Object(val schema: () -> Map<Int, Kind>) : Kind()
        class ListValue(val schema: () -> Map<Int, Kind>) : Kind()
    }
    fun text(id: Int) = fields[id] as? String
    fun int(id: Int, default: Int = 0) = fields[id] as? Int ?: default
    fun long(id: Int) = fields[id] as? Long ?: 0L
    fun bool(id: Int) = fields[id] as? Boolean ?: false
    fun bytes(id: Int) = fields[id] as? ByteArray
    fun binder(id: Int) = fields[id] as? IBinder
    fun obj(id: Int) = fields[id] as? DctRecord
    @Suppress("UNCHECKED_CAST") fun list(id: Int) = fields[id] as? List<DctRecord> ?: emptyList()
    override fun toString() = "DctRecord(redacted)"
    override fun describeContents() = Parcelable.CONTENTS_FILE_DESCRIPTOR
    override fun writeToParcel(p: Parcel, flags: Int) {
        fun sized(id: Int, body: () -> Unit) {
            p.writeInt(0xffff0000.toInt() or id); p.writeInt(0)
            val start = p.dataPosition(); body(); val end = p.dataPosition()
            p.setDataPosition(start - 4); p.writeInt(end - start); p.setDataPosition(end)
        }
        sized(20293) {
            fields.forEach { (id, value) -> sized(id) {
                when (val type = schema.getValue(id)) {
                    Kind.IntValue -> p.writeInt(value as Int)
                    Kind.LongValue -> p.writeLong(value as Long)
                    Kind.Bool -> p.writeInt(if (value as Boolean) 1 else 0)
                    Kind.Text -> p.writeString(value as String)
                    Kind.Bytes -> p.writeByteArray(value as ByteArray)
                    Kind.Binder -> p.writeStrongBinder(value as IBinder)
                    Kind.Ints -> p.writeIntArray(value as IntArray)
                    Kind.Strings -> p.writeStringArray(value as Array<String>)
                    Kind.UriValue, Kind.Fd -> (value as Parcelable).writeToParcel(p, flags)
                    Kind.ByteArrays -> { val items = value as List<ByteArray>; p.writeInt(items.size); items.forEach(p::writeByteArray) }
                    is Kind.Object -> (value as DctRecord).writeToParcel(p, flags)
                    is Kind.ListValue -> p.writeTypedList(value as List<DctRecord>)
                }
            } }
        }
    }
    override fun close() { fields.values.forEach { value ->
        when (value) { is Closeable -> runCatching { value.close() }; is List<*> -> value.filterIsInstance<Closeable>().forEach { runCatching { it.close() } } }
    } }
    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_ITEMS = 256
        fun read(p: Parcel, schema: Map<Int, Kind>, limit: Int = p.dataSize()): DctRecord {
            require(p.dataSize() <= 1024 * 1024) { "Oversized DCT Binder parcel" }
            fun header(): Pair<Int, Int> {
                require(p.dataPosition() + 4 <= limit)
                val h = p.readInt()
                val size = if (h ushr 16 == 0xffff) { require(p.dataPosition() + 4 <= limit); p.readInt() } else h ushr 16
                require(size >= 0 && size <= limit - p.dataPosition())
                return (h and 0xffff) to (p.dataPosition() + size)
            }
            val (magic, end) = header(); require(magic == 20293)
            val values = linkedMapOf<Int, Any>()
            try {
                while (p.dataPosition() < end) {
                    val (id, fieldEnd) = header(); require(fieldEnd <= end)
                    val kind = schema[id]
                    if (kind != null) {
                        require(id !in values) { "Duplicate DCT parcel field" }
                        val size = fieldEnd - p.dataPosition()
                        fun count(max: Int = MAX_ITEMS): Int { require(p.dataPosition() + 4 <= fieldEnd); return p.readInt().also { require(it in 0..max) } }
                        fun bytes(): ByteArray {
                            val position = p.dataPosition(); val length = count(MAX_BYTES)
                            require(length <= fieldEnd - p.dataPosition())
                            p.setDataPosition(position); return requireNotNull(p.createByteArray())
                        }
                        val value: Any? = if (size == 0) null else when (kind) {
                            Kind.IntValue -> { require(size == 4); p.readInt() }
                            Kind.LongValue -> { require(size == 8); p.readLong() }
                            Kind.Bool -> { require(size == 4); p.readInt() != 0 }
                            Kind.Text -> { require(size <= 131080); p.readString()?.also { require(it.length <= 65536) } }
                            Kind.Bytes -> bytes()
                            Kind.Binder -> p.readStrongBinder()
                            Kind.Ints -> IntArray(count()) { require(p.dataPosition() + 4 <= fieldEnd); p.readInt() }
                            Kind.Strings -> { val n = count(); Array(n) { requireNotNull(p.readString()).also { require(it.length <= 65536) } } }
                            Kind.UriValue -> Uri.CREATOR.createFromParcel(p)
                            Kind.Fd -> ParcelFileDescriptor.CREATOR.createFromParcel(p)
                            Kind.ByteArrays -> List(count()) { bytes() }
                            is Kind.Object -> read(p, kind.schema(), fieldEnd)
                            is Kind.ListValue -> {
                                val items = mutableListOf<DctRecord>()
                                try { repeat(count()) { require(p.readInt() > 0); items.add(read(p, kind.schema(), fieldEnd)) } }
                                catch (t: Throwable) { items.forEach { it.close() }; throw t }
                                items
                            }
                        }
                        if (value != null) values[id] = value
                        require(p.dataPosition() <= fieldEnd)
                    }
                    p.setDataPosition(fieldEnd)
                }
                require(p.dataPosition() == end)
                return DctRecord(schema, values)
            } catch (t: Throwable) { DctRecord(schema, values).close(); throw t }
        }
        fun nullable(p: Parcel, schema: Map<Int, Kind>): DctRecord? = when (p.readInt()) { 0 -> null; 1 -> read(p, schema); else -> error("Invalid DCT parcel presence") }
    }
}

internal object DctSchemas {
    private val I = DctRecord.Kind.IntValue; private val L = DctRecord.Kind.LongValue
    private val S = DctRecord.Kind.Text; private val B = DctRecord.Kind.Bytes
    private val C = DctRecord.Kind.Binder; private val Z = DctRecord.Kind.Bool
    private fun obj(s: () -> Map<Int, DctRecord.Kind>) = DctRecord.Kind.Object(s)
    private fun list(s: () -> Map<Int, DctRecord.Kind>) = DctRecord.Kind.ListValue(s)
    val element = mapOf(1 to I, 2 to I, 3 to B)
    val elements = mapOf(1 to list { element })
    val device = mapOf(1 to S, 2 to list { element }, 3 to S, 4 to S)
    val advertising = mapOf(5 to DctRecord.Kind.Ints, 6 to DctRecord.Kind.Ints, 15 to S, 16 to obj { elements })
    val connection = mapOf(11 to S)
    val start = mapOf(1 to C, 2 to S, 3 to obj { advertising }, 4 to C, 5 to B, 6 to S)
    val discovery = mapOf(1 to C, 2 to S, 4 to C, 5 to S)
    val connect = mapOf(1 to C, 2 to C, 3 to obj { connection }, 5 to I, 8 to S, 9 to obj { device })
    val accept = mapOf(1 to C, 3 to S, 5 to C, 6 to I, 9 to obj { device })
    val result = mapOf(1 to C)
    val disconnect = mapOf(1 to S, 2 to I, 5 to obj { device }, 6 to I)
    val reject = mapOf(1 to C, 2 to S, 3 to I, 6 to obj { device })
    val cancel = mapOf(1 to C, 2 to L)
    val parcelBytes = mapOf(1 to DctRecord.Kind.Fd)
    val header = mapOf(1 to S, 2 to S)
    val request = mapOf(1 to L, 2 to DctRecord.Kind.UriValue, 3 to S, 4 to list { header })
    val file = mapOf(1 to B, 2 to DctRecord.Kind.Fd, 3 to L, 4 to DctRecord.Kind.UriValue)
    val response = mapOf(1 to Z, 2 to L, 3 to S, 4 to DctRecord.Kind.ByteArrays, 5 to list { file }, 6 to Z, 7 to list { parcelBytes })
    val oneWay = mapOf(1 to S, 2 to obj { parcelBytes })
    val payload = mapOf(1 to L, 2 to I, 3 to B, 11 to obj { parcelBytes }, 15 to obj { request }, 16 to obj { response }, 17 to I, 18 to obj { oneWay })
    val send = mapOf(1 to C, 2 to DctRecord.Kind.Strings, 3 to obj { payload }, 4 to Z, 5 to I, 8 to obj { device })
    val startResult = mapOf(1 to I, 2 to S)
    val initiated = mapOf(1 to S, 4 to Z, 5 to B, 9 to I, 13 to obj { device })
    val connected = mapOf(1 to S, 2 to I, 4 to I, 7 to obj { device })
    val disconnected = mapOf(1 to S, 2 to I, 5 to obj { device }, 6 to I)
    val bandwidth = mapOf(1 to S, 2 to I, 3 to I, 4 to I, 11 to obj { device }, 12 to I)
    val received = mapOf(1 to S, 2 to obj { payload }, 4 to I, 7 to obj { device })
    val transfer = mapOf(1 to L, 2 to I, 3 to L, 4 to L)
    val update = mapOf(1 to S, 2 to obj { transfer }, 3 to I, 6 to obj { device })
    val found = mapOf(1 to S, 3 to S, 6 to I, 8 to obj { device })
    val rotation = mapOf(1 to S, 2 to S)
    val hint = mapOf(9 to S, 10 to S)
}
