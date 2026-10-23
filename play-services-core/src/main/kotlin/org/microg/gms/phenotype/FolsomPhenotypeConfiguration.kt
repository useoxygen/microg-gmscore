/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.google.android.gms.phenotype.Flag

/** A small local DeviceFlag store, shared by the provider, SDK reads and the native prompt.
 * The provider owns access in the default process; UI callers use ContentResolver, not preferences.
 * No remote assignment, experiment token or account value is synthesized here.
 */
object FolsomPhenotypeConfiguration {
    private const val PREFERENCES = "folsom_local_device_flags"
    const val CAPTURE_OVERRIDES = "folsom_capture_local_overrides"
    const val RESTORE_OVERRIDES = "folsom_restore_local_overrides"
    private val storeLock = Any()
    private val uri: Uri get() = Uri.parse("content://com.google.android.gms.phenotype/${FolsomPromptFlagPolicy.NAMESPACE}")

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    /** Every process reads the provider's current snapshot, never a process-local preferences cache. */
    internal fun read(context: Context): FolsomPromptFlags {
        val values = linkedMapOf<String, Any>()
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val keyColumn = cursor.getColumnIndexOrThrow("key"); val valueColumn = cursor.getColumnIndexOrThrow("value")
            while (cursor.moveToNext()) {
                val name = cursor.getString(keyColumn) ?: throw IllegalStateException("Missing flag name")
                val value = cursor.getString(valueColumn) ?: throw IllegalStateException("Missing flag value")
                check(!values.containsKey(name)) { "Duplicate device flag" }
                values[name] = FolsomPromptFlagPolicy.providerValue(name, value)
            }
        } ?: throw IllegalStateException("Local device flags are unavailable")
        check(values.keys == FolsomPromptFlagPolicy.types.keys) { "Incomplete local device flags" }
        return FolsomPromptFlagPolicy.resolve(FolsomPromptFlagPolicy.NAMESPACE, values)
    }

    @JvmStatic fun flags(context: Context, namespace: String?): Array<Flag>? {
        if (namespace != FolsomPromptFlagPolicy.NAMESPACE) return null
        val current = read(context)
        return arrayOf(Flag(FolsomPromptFlagPolicy.SKIP_CONSENT, current.skipConsent, 0),
            Flag(FolsomPromptFlagPolicy.ENABLE_NOTIFICATIONS, current.notificationsEnabled, 0),
            Flag(FolsomPromptFlagPolicy.NOTIFICATION_DELAY, current.notificationDelayMillis, 0))
    }

    @JvmStatic fun rows(context: Context, namespace: String?): Map<String, String>? {
        if (namespace != FolsomPromptFlagPolicy.NAMESPACE) return null
        // Called by ConfigurationProvider in its authoritative process. Do not recurse through read().
        val current = synchronized(storeLock) { FolsomPromptFlagPolicy.resolve(namespace, preferences(context).all) }
        return linkedMapOf(FolsomPromptFlagPolicy.SKIP_CONSENT to current.skipConsent.toString(),
            FolsomPromptFlagPolicy.ENABLE_NOTIFICATIONS to current.notificationsEnabled.toString(),
            FolsomPromptFlagPolicy.NOTIFICATION_DELAY to current.notificationDelayMillis.toString())
    }

    /** Private local writer contract: exact namespace, typed name/value, device scope only. */
    @JvmStatic fun update(context: Context, namespace: String?, values: ContentValues): Int {
        FolsomPromptFlagPolicy.requireLocalWriter(Binder.getCallingUid(), Process.myUid())
        require(namespace == FolsomPromptFlagPolicy.NAMESPACE) { "Unsupported flag namespace" }
        require(values.keySet() == setOf("name", "value")) { "Expected a device flag name and typed value" }
        val name = values.get("name") as? String ?: throw IllegalArgumentException("Missing flag name")
        val value = FolsomPromptFlagPolicy.checkedValue(name, values.get("value"))
        synchronized(storeLock) {
            val editor = preferences(context).edit()
            when (value) {
                is Boolean -> editor.putBoolean(name, value)
                is Long -> editor.putLong(name, value)
            }
            check(editor.commit()) { "Local flag override could not be saved" }
        }
        context.contentResolver.notifyChange(uri, null)
        return 1
    }

    /** Null name restores all three source defaults; a nonnull name restores that one default. */
    @JvmStatic fun delete(context: Context, namespace: String?, name: String?): Int {
        FolsomPromptFlagPolicy.requireLocalWriter(Binder.getCallingUid(), Process.myUid())
        require(namespace == FolsomPromptFlagPolicy.NAMESPACE) { "Unsupported flag namespace" }
        require(name == null || name in FolsomPromptFlagPolicy.types) { "Unsupported flag" }
        val count = synchronized(storeLock) {
            val prefs = preferences(context)
            val names = if (name == null) FolsomPromptFlagPolicy.types.keys else setOf(name)
            val count = names.count(prefs::contains)
            val editor = prefs.edit(); names.forEach(editor::remove)
            check(editor.commit()) { "Local flag override could not be removed" }
            count
        }
        context.contentResolver.notifyChange(uri, null)
        return count
    }

    private fun overrideValues(context: Context): Map<String, Any> = preferences(context).all.filterKeys {
        it in FolsomPromptFlagPolicy.types
    }.mapValues { (name, value) -> FolsomPromptFlagPolicy.checkedValue(name, value) }

    private fun bundle(values: Map<String, Any>) = Bundle().apply { values.forEach { (name, value) -> when (value) {
        is Boolean -> putBoolean(name, value)
        is Long -> putLong(name, value)
        else -> throw IllegalArgumentException("Unsupported flag value")
    } } }
    private fun values(bundle: Bundle): Map<String, Any> = bundle.keySet().associateWith {
        FolsomPromptFlagPolicy.checkedValue(it, bundle.get(it))
    }

    /** Same-UID capture/CAS-restore retains absent versus explicit defaults across processes. */
    @JvmStatic fun call(context: Context, method: String, namespace: String?, extras: Bundle?): Bundle? {
        if (method != CAPTURE_OVERRIDES && method != RESTORE_OVERRIDES) return null
        FolsomPromptFlagPolicy.requireLocalWriter(Binder.getCallingUid(), Process.myUid())
        require(namespace == FolsomPromptFlagPolicy.NAMESPACE) { "Unsupported flag namespace" }
        if (method == CAPTURE_OVERRIDES) {
            require(extras == null) { "Unexpected device flag arguments" }
            return synchronized(storeLock) { Bundle().apply {
                putBundle("overrides", bundle(overrideValues(context)))
                putInt("provider_pid", Process.myPid())
            } }
        }
        val scope = extras ?: throw IllegalArgumentException("Missing device flag restore scope")
        require(scope.keySet() == setOf("overrides", "expected")) { "Expected exact device flag restore scope" }
        val replacement = values(scope.getBundle("overrides") ?: throw IllegalArgumentException("Missing overrides"))
        val expected = values(scope.getBundle("expected") ?: throw IllegalArgumentException("Missing expected overrides"))
        synchronized(storeLock) {
            check(overrideValues(context) == expected) { "Local device flag overrides changed" }
            val editor = preferences(context).edit()
            FolsomPromptFlagPolicy.types.keys.forEach(editor::remove)
            replacement.forEach { (name, value) -> when (value) {
                is Boolean -> editor.putBoolean(name, value)
                is Long -> editor.putLong(name, value)
                else -> throw IllegalArgumentException("Unsupported flag value")
            } }
            check(editor.commit()) { "Local flag overrides could not be restored" }
        }
        context.contentResolver.notifyChange(uri, null)
        return Bundle().apply { putBoolean("restored", true) }
    }
}
