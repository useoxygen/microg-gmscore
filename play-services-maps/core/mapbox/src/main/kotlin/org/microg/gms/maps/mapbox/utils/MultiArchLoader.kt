/*
 * Copyright (C) 2019 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.maps.mapbox.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import com.mapbox.mapboxsdk.LibraryLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Loads the map engine's native library into the host app's process.
 *
 * Cyclon: the old loader copied the library over a single file in the app's cache and trusted a version stamp next
 * to it. Two processes of one app (for example an agent's copy of the app) could copy at the same time, and an
 * interrupted copy left a truncated library behind a valid stamp. Every later map then failed to load the library,
 * and because MapLibre only logs that failure, the app crashed on its first native call (useoxygen/cyclon CYC-765).
 *
 * Now the library is loaded from GmsCore's own install when its ABI matches the app's. Otherwise it is extracted into
 * a folder named after the library's CRC, written to a private temporary file, checked and renamed into place, so no
 * process ever sees a partial file and a copy that another process has loaded is never overwritten. A failed load is
 * retried once with a fresh copy.
 */
class MultiArchLoader(private val mapContext: Context, private val appContext: Context) : LibraryLoader() {
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun load(name: String) {
        val fileName = System.mapLibraryName(name)
        val appAbi = try {
            primaryCpuAbi(mapContext.packageManager.getApplicationInfo(appContext.packageName, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Can't read the ABI of ${appContext.packageName}", e)
            null
        } ?: processAbi()
        if (appAbi != null) {
            val gmsInfo = mapContext.applicationInfo
            val installed = gmsInfo.nativeLibraryDir?.let { File(it, fileName) }
            if (appAbi == primaryCpuAbi(gmsInfo) && installed != null && installed.isFile && tryLoad(installed)) return
            if (loadExtracted("lib/$appAbi/$fileName")) return
        }
        Log.d(TAG, "Loading native $name")
        System.loadLibrary(name)
    }

    private fun loadExtracted(path: String): Boolean {
        val apkFile = File(mapContext.packageCodePath)
        val (size, crc) = try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(path) ?: run {
                    Log.w(TAG, "Can't load native library: $path does not exist in $apkFile")
                    return false
                }
                entry.size to entry.crc
            }
        } catch (e: IOException) {
            Log.w(TAG, "Can't read $apkFile", e)
            return false
        }
        val abiDir = File(appContext.cacheDir, ".gmscore/${path.substringBeforeLast('/')}")
        val target = File(abiDir, "${java.lang.Long.toHexString(crc)}-$size/${path.substringAfterLast('/')}")
        removeOtherCopies(abiDir, target.parentFile!!)
        for (attempt in 1..2) {
            if (target.length() != size || attempt == 2) {
                if (!extract(apkFile, path, size, crc, target)) return false
            }
            if (tryLoad(target)) return true
        }
        return false
    }

    private fun extract(apkFile: File, path: String, size: Long, crc: Long, target: File): Boolean {
        val dir = target.parentFile!!
        dir.mkdirs()
        val temp = try {
            File.createTempFile(target.name, ".tmp", dir)
        } catch (e: IOException) {
            Log.w(TAG, "Can't create a temporary file in $dir", e)
            return false
        }
        try {
            val written = CRC32()
            var length = 0L
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(path) ?: return false
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written.update(buffer, 0, read)
                            length += read
                        }
                        output.fd.sync()
                    }
                }
            }
            if (length != size || written.value != crc) {
                Log.w(TAG, "Extracted $path has $length bytes, CRC ${java.lang.Long.toHexString(written.value)}; expected $size, ${java.lang.Long.toHexString(crc)}")
                return false
            }
            // rename() replaces atomically: a process loading the target sees either the old or the new complete file.
            if (!temp.renameTo(target)) {
                Log.w(TAG, "Can't move $temp to $target")
                return false
            }
            return true
        } catch (e: IOException) {
            Log.w(TAG, "Can't extract $path from $apkFile", e)
            return false
        } finally {
            temp.delete()
        }
    }

    /** Removes copies of other library versions and the old single-file layout. Unlinking a loaded file is safe. */
    private fun removeOtherCopies(abiDir: File, keep: File) {
        abiDir.listFiles()?.forEach { if (it != keep) it.deleteRecursively() }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun tryLoad(file: File): Boolean {
        Log.d(TAG, "Loading ${file.name} from ${file.path}")
        return try {
            System.load(file.absolutePath)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Can't load ${file.path}: ${e.message}")
            false
        }
    }

    /** An app without native code has no primary ABI; its process runs the device's preferred ABI of its bitness. */
    private fun processAbi(): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull()
    }

    private fun primaryCpuAbi(info: ApplicationInfo): String? {
        val abi = ApplicationInfo::class.java.getField("primaryCpuAbi").get(info) as String?
        return if (abi == "armeabi") "armeabi-v7a" else abi
    }

    companion object {
        private val TAG = "GmsMultiArchLoader"
    }

}
