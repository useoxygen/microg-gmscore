/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.os.Process
import android.os.Build
import android.provider.Settings

class PasswordWifiUnavailableException : Exception("Privileged Wi-Fi sync unavailable")

/** Account opt-in and state share the password vault's stable Gaia identity, AEAD and removal cleanup. */
class PasswordWifiSync(context: Context, private val repository: PasswordRepository, private val expectedUserId: String? = null) {
    private val context = context.applicationContext
    fun available(): Boolean = AndroidPasswordWifiPlatform.available(context)
    internal fun access(): PasswordWifiAccess = AndroidPasswordWifiPlatform.access(context)
    fun status(): PasswordWifiState = repository.store.locked { session -> verify(); PasswordWifiState.decode(session.load().wifiSync) }
    fun setEnabled(value: Boolean) {
        verify()
        if (value && !available()) throw PasswordWifiUnavailableException()
        repository.store.locked { session ->
            verify()
            val state = session.load()
            val wifi = PasswordWifiState.decode(state.wifiSync)
            wifi.enabled = value
            state.wifiSync = wifi.encode(); session.save(state)
        }
        if (value) repository.schedule()
    }
    /** Explicit owner action: clear recovery/history only, with neither remote nor native deletions. */
    fun pauseAndForget() {
        verify()
        repository.store.locked { session ->
            verify(); val state = session.load(); state.wifiSync = null; session.save(state)
        }
    }
    fun synchronizeIfEnabled(ownerAuthorizesRetry: Boolean = false): PasswordWifiState {
        val saved = status()
        if (!saved.enabled) return saved
        verify()
        if (!available()) throw PasswordWifiUnavailableException()
        val transport = repository.sharingTransport()
        val metadata = PasswordApi(transport).metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        val device = repository.wifiDeviceRegistration()
        return repository.store.locked { session ->
            verify()
            val state = session.load()
            val wifi = PasswordWifiState.decode(state.wifiSync)
            if (!wifi.enabled) return@locked wifi
            if (wifi.parent != null && wifi.parent != parent || state.pendingMetadata != null) throw VaultResetException()
            val keys = repository.sharingKeys(metadata, state)
            try {
                val token = device.token(session, state, parent)
                try { PasswordWifiMerge(PasswordWifiApi(transport), AndroidPasswordWifiPlatform(context, ::verify), ::verify)
                    .synchronize(session, state, parent, keys, token, ownerAuthorizesRetry) }
                finally { token.fill(0) }
            } finally { keys.destroy() }
        }
    }
    private fun verify() {
        repository.verifyWifiAccess()
        if (expectedUserId != null) {
            val accounts = AccountManager.get(context)
            if (!accounts.getAccountsByType("com.google").contains(repository.account) ||
                accounts.getUserData(repository.account, "GoogleUserId") != expectedUserId) throw VaultLockedException()
        }
    }
}

/** Actual native sink, guarded on every call. Privileged APIs are never replaced by a masked public list. */
internal class AndroidPasswordWifiPlatform(context: Context, private val verifyAccount: () -> Unit) : PasswordWifiPlatform {
    private val context = context.applicationContext
    private val wifi = this.context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: throw PasswordWifiUnavailableException()
    override val uid: Int get() = Process.myUid()
    private fun admit() {
        verifyAccount()
        if (!available(context)) throw PasswordWifiUnavailableException()
        if (!granted(context, "android.permission.NETWORK_SETTINGS")) {
            // On pre-Baklava, the non-recording app-op check is only an early rejection.
            // Note the actual location/scan access before and after the native boundary too.
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: throw PasswordWifiUnavailableException()
            @Suppress("DEPRECATION")
            if (ops.noteOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, uid, context.packageName) != AppOpsManager.MODE_ALLOWED ||
                ops.noteOpNoThrow(WIFI_SCAN_OP, uid, context.packageName) != AppOpsManager.MODE_ALLOWED) throw PasswordWifiUnavailableException()
        }
        verifyAccount()
    }
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun configurations(): List<WifiConfiguration> {
        admit()
        val records = try { WifiManager::class.java.getMethod("getPrivilegedConfiguredNetworks").invoke(wifi) as? List<WifiConfiguration> }
            catch (_: ReflectiveOperationException) { throw PasswordWifiUnavailableException() }
            ?: throw PasswordWifiUnavailableException()
        admit()
        return records
    }
    @Suppress("DEPRECATION")
    override fun snapshot(): List<PasswordWifiDeviceNetwork> {
        val records = configurations()
        admit()
        val connected = runCatching { wifi.connectionInfo }.getOrNull()?.takeIf { it.supplicantState == SupplicantState.COMPLETED }?.networkId
        admit()
        return records.mapNotNull { config -> convert(config, if (config.networkId == connected) System.currentTimeMillis() + 11_644_473_600_000L else 0L) }
    }
    @Suppress("DEPRECATION")
    private fun convert(config: WifiConfiguration, lastConnected: Long = 0): PasswordWifiDeviceNetwork? {
        val security = when {
            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> PasswordWifiNetwork.PSK
            config.allowedKeyManagement.get(8) -> PasswordWifiNetwork.PSK // Keep SAE as an ineligible identity blocker; never downgrade it.
            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) && config.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.SHARED) -> PasswordWifiNetwork.WEP
            else -> return null // Enterprise/open/SAE-only have no safe representable round-trip.
        }
        val ssid = config.SSID ?: throw ProtocolException()
        val hex = if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"'))
            ssid.substring(1, ssid.length - 1).toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it.toInt() and 255) }
            else ssid
        val credential = if (security == PasswordWifiNetwork.PSK) config.preSharedKey
            else config.wepKeys.getOrNull(config.wepTxKeyIndex)
        val network = PasswordWifiNetwork.create(hex, security, PasswordWifiNetwork.frameworkValue(credential ?: throw ProtocolException()),
            PasswordWifiNetwork.syncedMetered(intField(config, "meteredOverride")), lastConnected)
        val ephemeral = try { WifiConfiguration::class.java.getMethod("isEphemeral").invoke(config) as Boolean }
            catch (_: ReflectiveOperationException) { throw PasswordWifiUnavailableException() }
        return PasswordWifiDeviceNetwork(config.networkId, intField(config, "creatorUid"),
            !config.hiddenSSID && !ephemeral && !config.allowedKeyManagement.get(8) && config.status != WifiConfiguration.Status.DISABLED, network)
    }
    @Suppress("DEPRECATION")
    private fun build(network: PasswordWifiNetwork, base: WifiConfiguration? = null): WifiConfiguration {
        val result = base?.let(::WifiConfiguration) ?: WifiConfiguration()
        result.SSID = network.hexSsid // Framework accepts exact unquoted hex, including non-UTF8 SSIDs.
        result.allowedKeyManagement.clear(); result.allowedAuthAlgorithms.clear()
        result.preSharedKey = null
        result.wepKeys.fill(null)
        if (network.security == PasswordWifiNetwork.WEP) {
            result.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            result.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            result.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            result.wepKeys[0] = PasswordWifiNetwork.frameworkKey(network); result.wepTxKeyIndex = 0
        } else {
            result.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            result.preSharedKey = PasswordWifiNetwork.frameworkKey(network)
        }
        setIntField(result, "meteredOverride", PasswordWifiNetwork.frameworkMetered(network.metered))
        if (base == null) try { WifiConfiguration::class.java.getField("shared").setBoolean(result, false) }
            catch (_: ReflectiveOperationException) { throw PasswordWifiUnavailableException() }
        return result
    }
    @Suppress("DEPRECATION")
    override fun add(network: PasswordWifiNetwork): Int {
        if (snapshot().any { it.network.tag() == network.tag() }) throw ConflictException()
        val config = build(network)
        admit()
        val id = wifi.addNetwork(config)
        if (id >= 0) { admit(); wifi.enableNetwork(id, false) }
        admit()
        return id
    }
    @Suppress("DEPRECATION")
    override fun update(before: PasswordWifiDeviceNetwork, network: PasswordWifiNetwork): Int {
        val current = unchanged(before)
        val config = build(network, current)
        admit()
        val id = wifi.updateNetwork(config)
        admit()
        return id
    }
    @Suppress("DEPRECATION")
    override fun remove(before: PasswordWifiDeviceNetwork): Boolean {
        unchanged(before)
        admit()
        val result = wifi.removeNetwork(before.networkId)
        admit()
        return result
    }
    private fun unchanged(before: PasswordWifiDeviceNetwork): WifiConfiguration {
        val config = configurations().singleOrNull { it.networkId == before.networkId } ?: throw ConflictException()
        val current = convert(config) ?: throw ConflictException()
        if (!current.eligible || current.creatorUid != uid || current.creatorUid != before.creatorUid ||
            !current.network.sameConfiguration(before.network)) throw ConflictException()
        return config
    }
    private fun intField(config: WifiConfiguration, name: String): Int = try { WifiConfiguration::class.java.getField(name).getInt(config) }
        catch (_: ReflectiveOperationException) { throw PasswordWifiUnavailableException() }
    private fun setIntField(config: WifiConfiguration, name: String, value: Int) {
        try { WifiConfiguration::class.java.getField(name).setInt(config, value) }
        catch (_: ReflectiveOperationException) { throw PasswordWifiUnavailableException() }
    }
    companion object {
        // Framework OPSTR_WIFI_SCAN is hidden; this is its exact Android app-op name.
        private const val WIFI_SCAN_OP = "android:wifi_scan"
        fun available(context: Context): Boolean = access(context) == PasswordWifiAccess.AVAILABLE
        internal fun access(context: Context): PasswordWifiAccess = try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val settings = granted(context, "android.permission.NETWORK_SETTINGS")
            val enabled = if (settings) true else if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
                else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
            fun allowed(op: String): Boolean {
                if (settings) return true
                if (ops == null) return false
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
                    else ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
                return mode == AppOpsManager.MODE_ALLOWED
            }
            PasswordWifiAccessPolicy.evaluate(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion,
                context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                granted(context, "android.permission.READ_WIFI_CREDENTIAL"), granted(context, "android.permission.ACCESS_WIFI_STATE"),
                granted(context, "android.permission.CHANGE_WIFI_STATE"), settings, granted(context, "android.permission.ACCESS_FINE_LOCATION"),
                enabled, allowed(AppOpsManager.OPSTR_FINE_LOCATION), allowed(WIFI_SCAN_OP))
        } catch (_: Exception) { PasswordWifiAccess.IMAGE_GRANT_REQUIRED }
        private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
