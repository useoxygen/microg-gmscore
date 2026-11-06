/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.calendar.CalendarSyncPreferences
import org.microg.gms.people.ContactSyncPreferences
import org.microg.gms.people.sync.SyncMode
import org.microg.tools.ui.AbstractSettingsActivity
import org.microg.tools.ui.ResourceSettingsFragment

/**
 * Contacts sync, Calendar sync, Passwords and password filling in one place, each with its state in plain words, so owners need not
 * find them under Google accounts › Account settings (CYC-1467). The rows open the existing screens; the state comes from the
 * same reader as Cyclon Services health, so the two never disagree.
 */
internal object SyncPasswords {
    const val PREF_CONTACTS = "pref_sync_contacts"
    const val PREF_CALENDAR = "pref_sync_calendar"
    const val PREF_PASSWORDS = "pref_sync_passwords"
    const val PREF_AUTOFILL = "pref_sync_autofill"

    /** How many of the owner's Google accounts sync this; total 0 when it could not be read. */
    data class Accounts(val on: Int, val total: Int)
    /** A row's words: a string resource and its arguments; with [count], a plurals resource chosen by it. */
    data class Summary(val text: Int, val args: List<Any> = emptyList(), val count: Int? = null)

    fun contactsSummary(reading: HealthReading, accounts: Accounts = Accounts(0, 0)) = summary(reading, accounts, R.string.cyclon_sync_contacts_off)
    fun calendarSummary(reading: HealthReading, accounts: Accounts = Accounts(0, 0)) = summary(reading, accounts, R.string.cyclon_sync_calendar_off)
    fun autofillSummary(autofillOn: Boolean): Int = if (autofillOn) R.string.cyclon_autofill_on else R.string.cyclon_autofill_off

    private fun summary(reading: HealthReading, accounts: Accounts, off: Int): Summary = when (reading.state) {
        HealthState.OFF -> Summary(if (reading.reason == HealthReason.NO_ACCOUNTS) R.string.cyclon_sync_no_account else off)
        HealthState.PAUSED -> Summary(R.string.cyclon_sync_paused)
        // With several accounts, say when only some sync, so "On" never hides an account that is off.
        HealthState.READY, HealthState.CONNECTED -> when {
            accounts.total > 1 && accounts.on in 1 until accounts.total -> Summary(
                if (reading.reason == HealthReason.SYNC_PENDING) R.plurals.cyclon_sync_pending_some else R.plurals.cyclon_sync_on_some,
                listOf(accounts.on, accounts.total), count = accounts.total)
            reading.reason == HealthReason.SYNC_PENDING -> Summary(R.string.cyclon_sync_pending)
            else -> Summary(R.string.cyclon_sync_on)
        }
        HealthState.ATTENTION -> Summary(R.string.cyclon_sync_attention)
        HealthState.UNKNOWN -> Summary(R.string.cyclon_sync_unknown)
    }

    fun autofillEnabled(context: Context): Boolean =
        runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }.getOrDefault(false)

    /**
     * Off: Android's own "Use Cyclon Services to fill passwords?" prompt. On: Android's page for choosing (or turning
     * off) the password service, where there is one. Android, not this app, makes the change.
     */
    fun autofillIntent(context: Context, on: Boolean): Intent? = when {
        !on -> Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
        else -> listOf(Intent(CREDENTIAL_PROVIDER_SETTINGS, Uri.parse("package:${context.packageName}")), Intent(Settings.ACTION_SETTINGS))
            .firstOrNull { it.resolveActivity(context.packageManager) != null }
    }
    // Settings.ACTION_CREDENTIAL_PROVIDER (API 34); Settings resolves it only with the provider's package: URI.
    private const val CREDENTIAL_PROVIDER_SETTINGS = "android.settings.CREDENTIAL_PROVIDER"
}

private fun Preference.show(summary: SyncPasswords.Summary) {
    val args = summary.args.toTypedArray()
    this.summary = summary.count?.let { context.resources.getQuantityString(summary.text, it, *args) } ?: context.getString(summary.text, *args)
}

/**
 * Keeps the rows' summaries current while the page is shown: on return from a detail screen, and every few seconds,
 * so a first sync finishing or failing with the page open shows without leaving it. Call from onViewCreated.
 */
internal fun PreferenceFragmentCompat.keepSyncPasswordsStateFresh() {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                showSyncPasswordsState()
                delay(REFRESH_MS)
            }
        }
    }
}
private const val REFRESH_MS = 5_000L

private suspend fun PreferenceFragmentCompat.showSyncPasswordsState() {
    val context = requireContext().applicationContext
    val autofillOn = SyncPasswords.autofillEnabled(context)
    findPreference<Preference>(SyncPasswords.PREF_AUTOFILL)?.apply {
        setSummary(SyncPasswords.autofillSummary(autofillOn))
        intent = SyncPasswords.autofillIntent(context, autofillOn)
    }
    val snapshot = ServiceHealthReader(context).read()
    val (contacts, calendar) = withContext(Dispatchers.IO) {
        runCatching {
            val accounts = DeviceAccount.visible(context, AccountManager.get(context).getAccountsByType("com.google")).toList()
            SyncPasswords.Accounts(accounts.count { ContactSyncPreferences(context, it).mode != SyncMode.OFF }, accounts.size) to
                SyncPasswords.Accounts(accounts.count { CalendarSyncPreferences(context, it).enabled }, accounts.size)
        }.getOrDefault(SyncPasswords.Accounts(0, 0) to SyncPasswords.Accounts(0, 0))
    }
    if (!isAdded) return
    findPreference<Preference>(SyncPasswords.PREF_CONTACTS)?.show(SyncPasswords.contactsSummary(snapshot.contacts, contacts))
    findPreference<Preference>(SyncPasswords.PREF_CALENDAR)?.show(SyncPasswords.calendarSummary(snapshot.calendar, calendar))
}

/** The page itself: the rows on their own, for Settings' tile, Android's Google account page and Core. */
class SyncPasswordsFragment : ResourceSettingsFragment() {
    init { preferencesResource = R.xml.preferences_sync_passwords }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        keepSyncPasswordsStateFresh()
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
