/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.os.Build
import android.view.autofill.AutofillManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import kotlinx.coroutines.launch
import org.microg.tools.ui.AbstractSettingsActivity
import org.microg.tools.ui.ResourceSettingsFragment

/**
 * Contacts sync, Calendar sync and Passwords in one place, each with its state in plain words, so owners need not
 * find them under Google accounts › Account settings (CYC-1467). The rows open the existing screens; the state comes from the
 * same reader as Cyclon Services health, so the two never disagree.
 */
internal object SyncPasswords {
    const val PREF_CONTACTS = "pref_sync_contacts"
    const val PREF_CALENDAR = "pref_sync_calendar"
    const val PREF_PASSWORDS = "pref_sync_passwords"

    fun contactsSummary(reading: HealthReading): Int = summary(reading, R.string.cyclon_sync_contacts_off)
    fun calendarSummary(reading: HealthReading): Int = summary(reading, R.string.cyclon_sync_calendar_off)
    fun passwordsSummary(autofillOn: Boolean): Int =
        if (autofillOn) R.string.cyclon_passwords_autofill_on else R.string.cyclon_passwords_manage

    private fun summary(reading: HealthReading, off: Int): Int = when (reading.state) {
        HealthState.OFF -> if (reading.reason == HealthReason.NO_ACCOUNTS) R.string.cyclon_sync_no_account else off
        HealthState.PAUSED -> R.string.cyclon_sync_paused
        HealthState.READY, HealthState.CONNECTED ->
            if (reading.reason == HealthReason.SYNC_PENDING) R.string.cyclon_sync_pending else R.string.cyclon_sync_on
        HealthState.ATTENTION -> R.string.cyclon_sync_attention
        HealthState.UNKNOWN -> R.string.cyclon_sync_unknown
    }
}

/** Fills the three rows' summaries; call from onResume so a change made on a detail screen shows on return. */
internal fun PreferenceFragmentCompat.showSyncPasswordsState() {
    val context = requireContext().applicationContext
    val autofillOn = Build.VERSION.SDK_INT >= 26 &&
        runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false)
    findPreference<Preference>(SyncPasswords.PREF_PASSWORDS)?.setSummary(SyncPasswords.passwordsSummary(autofillOn))
    lifecycleScope.launch {
        val snapshot = ServiceHealthReader(context).read()
        if (!isAdded) return@launch
        findPreference<Preference>(SyncPasswords.PREF_CONTACTS)?.setSummary(SyncPasswords.contactsSummary(snapshot.contacts))
        findPreference<Preference>(SyncPasswords.PREF_CALENDAR)?.setSummary(SyncPasswords.calendarSummary(snapshot.calendar))
    }
}

/** The page itself: the rows on their own, for Settings' tile, Android's Google account page and Core. */
class SyncPasswordsFragment : ResourceSettingsFragment() {
    init { preferencesResource = R.xml.preferences_sync_passwords }

    override fun onResume() {
        super.onResume()
        showSyncPasswordsState()
    }
}

/**
 * Hosts the page in its own task (taskAffinity ""), so Back and Up return to whatever opened it (Settings, the
 * Google account page, Home) rather than to the Cyclon Services home or an earlier Cyclon Services screen.
 */
class SyncPasswordsActivity : AbstractSettingsActivity() {
    init { showHomeAsUp = true }
    override fun getFragment(): Fragment = SyncPasswordsFragment()
}
