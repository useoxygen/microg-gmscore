/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.gms.BuildConfig
import com.google.android.gms.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.microg.gms.people.ContactsSyncActivity
import java.text.DateFormat
import java.util.Date

class CyclonHealthFragment : Fragment() {
    private var snapshot by mutableStateOf<ServiceHealthSnapshot?>(null)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CyclonMaterialTheme { HealthScreen(snapshot, ::navigate, ::share) } }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val reader = ServiceHealthReader(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshot = null
                while (true) {
                    snapshot = reader.read()
                    delay(15_000)
                }
            }
        }
    }

    private fun navigate(destination: Int) {
        if (destination == R.id.cyclon_contacts_health_action) startActivity(Intent(requireContext(), ContactsSyncActivity::class.java))
        else if (destination == R.id.cyclon_calendar_health_action) startActivity(Intent(requireContext(), org.microg.gms.calendar.CalendarSyncActivity::class.java))
        else findNavController().navigate(destination)
    }

    private fun share(report: String) {
        // Sharing is owner initiated, after a preview. No receiver is chosen or submitted here.
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }, getString(R.string.cyclon_health_share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.cyclon_health_share_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun HealthScreen(snapshot: ServiceHealthSnapshot?, navigate: (Int) -> Unit, share: (String) -> Unit) {
    // Freeze the preview: the owner shares precisely the text they reviewed even when readings refresh.
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(report != null) { report = null }
    val view = LocalView.current
    LaunchedEffect(report != null) {
        generateSequence(view.parent) { it.parent }.filterIsInstance<androidx.core.widget.NestedScrollView>()
            .firstOrNull()?.post { generateSequence(view.parent) { it.parent }
                .filterIsInstance<androidx.core.widget.NestedScrollView>().firstOrNull()?.scrollTo(0, 0) }
    }
    // The host's NestedScrollView owns scrolling, including large accessibility fonts.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (report != null) {
            CyclonTextButton(onClick = { report = null }) { Text(stringResource(R.string.cyclon_back)) }
            Text(stringResource(R.string.cyclon_health_report), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.cyclon_health_report_privacy))
            SelectionContainer { Text(report!!, style = MaterialTheme.typography.bodyMedium) }
            CyclonOutlinedButton(onClick = { share(report!!) }) { Text(stringResource(R.string.cyclon_health_share)) }
        } else {
            Text(stringResource(R.string.cyclon_health_intro))
            Text(stringResource(R.string.cyclon_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (snapshot == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.cyclon_health_loading))
            } else {
                HealthCard(R.string.service_name_mcs, snapshot.push, R.string.cyclon_health_push_action, R.id.gcmFragment, navigate)
                HealthCard(R.string.contacts_sync_title, snapshot.contacts, R.string.cyclon_health_contacts_action, R.id.cyclon_contacts_health_action, navigate)
                HealthCard(R.string.cyclon_calendar_title, snapshot.calendar, R.string.cyclon_calendar_title, R.id.cyclon_calendar_health_action, navigate)
                HealthCard(R.string.cyclon_health_location, snapshot.location, R.string.cyclon_health_location_action, R.id.nav_location, navigate)
                HealthCard(R.string.service_name_checkin, snapshot.registration, R.string.cyclon_health_registration_action, R.id.checkinFragment, navigate)
                Text(stringResource(R.string.cyclon_health_observed, time(snapshot.observedAt)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            CyclonOutlinedButton(onClick = { navigate(R.id.selfcheckFragment) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(org.microg.tools.ui.R.string.self_check_title))
            }
            CyclonOutlinedButton(onClick = { navigate(R.id.cyclonAppTroubleshootingFragment) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cyclon_apps_title))
            }
            CyclonOutlinedButton(onClick = { report = snapshot?.diagnosticReport() }, enabled = snapshot != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cyclon_health_report))
            }
        }
    }
}

@Composable
private fun HealthCard(title: Int, reading: HealthReading, action: Int, destination: Int, navigate: (Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (reading.state) {
                HealthState.OFF -> R.string.cyclon_health_off
                HealthState.PAUSED -> R.string.cyclon_health_paused
                HealthState.READY -> R.string.cyclon_health_ready
                HealthState.CONNECTED -> R.string.cyclon_health_connected
                HealthState.ATTENTION -> R.string.cyclon_health_attention
                HealthState.UNKNOWN -> R.string.cyclon_health_unknown
            }), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(reasonText(reading.reason)), style = MaterialTheme.typography.bodyMedium)
            reading.lastSuccessAt?.let { Text(stringResource(R.string.cyclon_health_last_success, time(it)), style = MaterialTheme.typography.bodyMedium) }
            CyclonTextButton(onClick = { navigate(destination) }) { Text(stringResource(action)) }
        }
    }
}

private fun time(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
private fun reasonText(reason: HealthReason): Int = when (reason) {
    HealthReason.DISABLED -> R.string.cyclon_health_disabled
    HealthReason.UNREADABLE -> R.string.cyclon_health_unreadable
    HealthReason.CONNECTED -> R.string.cyclon_health_push_connected
    HealthReason.DISCONNECTED -> R.string.cyclon_health_push_disconnected
    HealthReason.NO_ACCOUNTS -> R.string.contacts_sync_no_accounts
    HealthReason.SYNC_PAUSED -> R.string.cyclon_health_sync_paused
    HealthReason.SYNC_PENDING -> R.string.cyclon_health_sync_pending
    HealthReason.SYNC_SUCCESS -> R.string.cyclon_health_sync_success
    HealthReason.CONTACT_PERMISSION -> R.string.cyclon_health_contacts_permission
    HealthReason.AUTHORIZATION -> R.string.contacts_sync_authorization
    HealthReason.NETWORK -> R.string.contacts_sync_network
    HealthReason.LOCAL -> R.string.contacts_sync_local
    HealthReason.CONFLICT -> R.string.contacts_sync_conflict
    HealthReason.UNCERTAIN -> R.string.contacts_sync_uncertain
    HealthReason.LOCATION_READY -> R.string.cyclon_health_location_ready
    HealthReason.LOCATION_PERMISSION -> R.string.cyclon_health_location_permission
    HealthReason.REGISTRATION_PENDING -> R.string.cyclon_health_registration_pending
    HealthReason.REGISTRATION_RECORDED -> R.string.cyclon_health_registration_recorded
    HealthReason.CALENDAR_PERMISSION -> R.string.cyclon_calendar_permissions
}
