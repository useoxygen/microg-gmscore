/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.microg.gms.people.sync.ContactPhotos
import org.microg.gms.people.sync.UnreadableContact
import java.io.ByteArrayOutputStream

/** Bound decoding and Binder size; ContactsProvider builds its display photo and thumbnail. */
object PhotoEncoding {
    fun providerBytes(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size > ContactPhotos.MAX_BYTES) throw UnreadableContact()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) throw UnreadableContact()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw UnreadableContact()
        try {
            while (true) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                if (out.size() <= 256 * 1024) return out.toByteArray()
                val next = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width / 2), maxOf(1, bitmap.height / 2), true)
                if (next === bitmap) throw UnreadableContact()
                bitmap.recycle(); bitmap = next
            }
        } finally { bitmap.recycle() }
    }
}
