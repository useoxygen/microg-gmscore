/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import com.google.android.gms.R
import kotlinx.coroutines.*
import org.microg.gms.ui.AppHeadingPreference

/** No installed-app inventory is collected: the system picker returns the owner-selected component. */
class AppTroubleshootingFragment : PreferenceFragmentCompat() {
    private val selected get() = arguments?.getString("package")?.takeIf { it.length in 1..255 }
    private var displayed: List<Any?>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.component?.packageName?.takeIf { result.resultCode == Activity.RESULT_OK }?.let {
            findNavController().navigate(R.id.cyclonAppTroubleshootingFragment, bundleOf("package" to it))
        }
    }
    override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
        displayed = null
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
    override fun onViewCreated(view: android.view.View, state: Bundle?) {
        super.onViewCreated(view, state)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val name = selected
                if (name == null) {
                    preferenceScreen.removeAll()
                    item(R.string.cyclon_apps_title, R.string.cyclon_apps_description)
                    item(R.string.cyclon_apps_choose, R.string.cyclon_apps_choose_description) {
                        try { picker.launch(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT,
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))) }
                        catch (_: Exception) { unavailable() }
                    }
                    item(R.string.cyclon_apps_push_list, R.string.cyclon_apps_push_list_description) { findNavController().navigate(R.id.gcmAllAppsFragment) }
                } else while (true) { details(name); delay(15_000) }
            }
        }
    }
    private fun item(title: Int, text: Int, action: (() -> Unit)? = null) {
        preferenceScreen.addPreference(Preference(requireContext()).apply {
            setTitle(title); setSummary(text); isSelectable = action != null; isIconSpaceReserved = false
            onPreferenceClickListener = Preference.OnPreferenceClickListener { action?.invoke(); true }
        })
    }
    private suspend fun details(name: String) {
        val context = requireContext().applicationContext
        val observation = withContext(Dispatchers.IO) {
            val info = runCatching { context.packageManager.getApplicationInfo(name, 0) }.getOrNull()
            val app = runCatching {
                val response = context.contentResolver.call(Uri.parse("content://${context.packageName}.cyclon.push"), PushStateProvider.METHOD_APPS, name, null)
                check(response?.getInt(PushStateProvider.KEY_VERSION) == PushStateProvider.VERSION)
                val row = response!!.getParcelableArrayList<Bundle>("apps").orEmpty().singleOrNull()
                if (row == null) AppPushObservation(false, true, null, 0)
                else AppPushObservation(row.getBoolean("registered"), row.getBoolean("allow_register"),
                    row.getString("last_error")?.takeIf { it in PushStateProvider.ERROR_CODES }, row.getLong("last_message_at"))
            }.getOrNull()
            fun permission(value: String): Boolean? = info?.let { context.packageManager.checkPermission(value, name) == PackageManager.PERMISSION_GRANTED }
            Observation(info?.let { it.enabled && (Build.VERSION.SDK_INT < 24 || it.flags and ApplicationInfo.FLAG_SUSPENDED == 0) }, app,
                if (Build.VERSION.SDK_INT >= 33) permission(Manifest.permission.POST_NOTIFICATIONS) else null,
                permission(Manifest.permission.ACCESS_COARSE_LOCATION)?.let { it || permission(Manifest.permission.ACCESS_FINE_LOCATION) == true },
                ServiceHealthReader(context).read())
        }
        val signature = listOf(name, observation.enabled, observation.push, observation.notifications,
            observation.locationPermission, observation.health.push, observation.health.location)
        if (signature == displayed) return
        displayed = signature
        preferenceScreen.removeAll()
        preferenceScreen.addPreference(AppHeadingPreference(requireContext()).apply { packageName = name })
        item(R.string.cyclon_apps_title, R.string.cyclon_apps_description)
        reading(R.string.service_name_mcs, appPushReading(observation.enabled, observation.health.push, observation.push), name)
        observation.push?.lastMessageAt?.takeIf { it > 0 }?.let { timestamp ->
            preferenceScreen.addPreference(Preference(requireContext()).apply {
                setTitle(R.string.cyclon_apps_last_delivery)
                summary = DateUtils.getRelativeDateTimeString(requireContext(), timestamp, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
                isSelectable = false; isIconSpaceReserved = false
            })
        }
        reading(R.string.cyclon_apps_notifications, AppReading(when(observation.notifications) {
            true -> AppReason.NOTIFICATION_ALLOWED; false -> AppReason.NOTIFICATION_DENIED; null -> AppReason.UNREADABLE
        }, AppRecovery.APP_SETTINGS), name)
        reading(R.string.cyclon_health_location, appLocationReading(observation.health.location, observation.locationPermission), name)
        reading(R.string.cyclon_apps_signin, AppReading(AppReason.NOT_TESTED, AppRecovery.ACCOUNTS), name)
        item(R.string.cyclon_apps_open, R.string.cyclon_apps_test_description) { recover(AppRecovery.OPEN_APP, name) }
    }
    private data class Observation(val enabled: Boolean?, val push: AppPushObservation?, val notifications: Boolean?,
        val locationPermission: Boolean?, val health: ServiceHealthSnapshot)
    private fun reading(title: Int, value: AppReading, name: String) {
        preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(title); isIconSpaceReserved = false })
        // Static allowlisted resource names, never raw server responses or tokens.
        val reason = when(value.reason) {
            AppReason.UNREADABLE -> R.string.cyclon_app_reason_unreadable
            AppReason.APP_OFF -> R.string.cyclon_app_reason_app_off
            AppReason.PUSH_OFF -> R.string.cyclon_app_reason_push_off
            AppReason.APP_BLOCKED -> R.string.cyclon_app_reason_app_blocked
            AppReason.DISCONNECTED -> R.string.cyclon_app_reason_disconnected
            AppReason.AUTHORIZATION -> R.string.cyclon_app_reason_authorization
            AppReason.NETWORK -> R.string.cyclon_app_reason_network
            AppReason.REGISTRATION_ERROR -> R.string.cyclon_app_reason_registration_error
            AppReason.REGISTERED -> R.string.cyclon_app_reason_registered
            AppReason.NO_REGISTRATION -> R.string.cyclon_app_reason_no_registration
            AppReason.NOTIFICATION_ALLOWED -> R.string.cyclon_app_reason_notification_allowed
            AppReason.NOTIFICATION_DENIED -> R.string.cyclon_app_reason_notification_denied
            AppReason.LOCATION_READY -> R.string.cyclon_app_reason_location_ready
            AppReason.LOCATION_PERMISSION -> R.string.cyclon_app_reason_location_permission
            AppReason.LOCATION_OFF -> R.string.cyclon_app_reason_location_off
            AppReason.NOT_TESTED -> R.string.cyclon_app_reason_not_tested
        }
        val recovery = when(value.recovery) {
            AppRecovery.APP_SETTINGS -> R.string.cyclon_app_recovery_app_settings
            AppRecovery.PUSH_SETTINGS -> R.string.cyclon_app_recovery_push_settings
            AppRecovery.PUSH_APP_SETTINGS -> R.string.cyclon_app_recovery_push_app_settings
            AppRecovery.LOCATION_SETTINGS -> R.string.cyclon_app_recovery_location_settings
            AppRecovery.ACCOUNTS -> R.string.cyclon_app_recovery_accounts
            AppRecovery.OPEN_APP -> R.string.cyclon_app_recovery_open_app
        }
        item(reason, recovery) { recover(value.recovery, name) }
    }
    private fun recover(action: AppRecovery, name: String) {
        try {
            when (action) {
                AppRecovery.APP_SETTINGS -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", name, null)))
                AppRecovery.PUSH_SETTINGS -> findNavController().navigate(R.id.gcmFragment)
                AppRecovery.PUSH_APP_SETTINGS -> findNavController().navigate(R.id.gcmAppFragment, bundleOf("package" to name))
                AppRecovery.LOCATION_SETTINGS -> findNavController().navigate(R.id.nav_location)
                AppRecovery.ACCOUNTS -> findNavController().navigate(R.id.accountManagerFragment)
                AppRecovery.OPEN_APP -> startActivity(requireNotNull(requireContext().packageManager.getLaunchIntentForPackage(name)))
            }
        } catch (_: Exception) { unavailable() }
    }
    private fun unavailable() { Toast.makeText(requireContext(), R.string.cyclon_apps_unavailable, Toast.LENGTH_LONG).show() }
}
