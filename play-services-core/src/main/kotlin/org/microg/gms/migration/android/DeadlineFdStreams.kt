/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking owned descriptors: cancellation must not wait behind a blocked pipe.
 * The caller's descriptor is duplicated before constructing either stream. */
internal object DeadlineFdStreams {
    private class Owned(val fd: ParcelFileDescriptor, nonblocking: Boolean) : Closeable {
        val closed = AtomicBoolean(false)
        init { if (nonblocking) { val flags = Os.fcntlInt(fd.fileDescriptor,OsConstants.F_GETFL,0); Os.fcntlInt(fd.fileDescriptor,OsConstants.F_SETFL,flags or OsConstants.O_NONBLOCK) } }
        fun transfer(bytes: ByteArray, offset: Int, size: Int, write: Boolean): Int {
            val deadline = System.nanoTime()+TimeUnitNanos
            while (!closed.get()) {
                if(System.nanoTime() >= deadline) throw IOException("Migration descriptor timed out")
                val poll = StructPollfd().apply { this.fd = this@Owned.fd.fileDescriptor; events = (if(write) OsConstants.POLLOUT else OsConstants.POLLIN).toShort() }
                try { if(Os.poll(arrayOf(poll),100) == 0) continue } catch(e: ErrnoException) { if(e.errno == OsConstants.EINTR) continue; throw IOException("Migration poll failed",e) }
                if(closed.get()) break
                try { return if(write) Os.write(fd.fileDescriptor,bytes,offset,size) else Os.read(fd.fileDescriptor,bytes,offset,size) }
                catch(e: ErrnoException) { if(e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw IOException("Migration descriptor failed",e) }
            }
            throw IOException("Migration descriptor closed")
        }
        override fun close() { if(closed.compareAndSet(false,true)) fd.close() }
    }
    // GMS efkf/efkg: byte0 means successful EOF; byte1 reports a producer error.
    fun requireProducerSuccess(input: InputStream) { require(input.read()==0) { "Nearby stream producer failed" } }
    private const val TimeUnitNanos = 30_000_000_000L
    fun input(owned: ParcelFileDescriptor): InputStream {
        val handle = Owned(owned,false)
        return object : InputStream() {
            override fun read(): Int { val one=ByteArray(1); return if(read(one,0,1)==-1) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, size: Int): Int { require(offset>=0 && size>=0 && offset<=bytes.size-size); if(size==0)return 0; return handle.transfer(bytes,offset,size,false).let { if(it==0)-1 else it } }
            override fun close() = handle.close()
        }
    }
    fun output(owned: ParcelFileDescriptor): OutputStream {
        val handle = Owned(owned,true)
        return object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()),0,1)
            override fun write(bytes: ByteArray, offset: Int, size: Int) { require(offset>=0 && size>=0 && offset<=bytes.size-size); var done=0; while(done<size) { val count=handle.transfer(bytes,offset+done,size-done,true); if(count<=0)throw IOException("Migration pipe stopped"); done+=count } }
            override fun close() = handle.close()
        }
    }
}
