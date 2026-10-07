/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cyclon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.google.android.gms.BuildConfig
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class Notice(val title: String, val license: String, val text: String)

class CyclonAboutFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CyclonAbout() }
        }
}

@Composable
private fun CyclonAbout() {
    val context = LocalContext.current
    var notices by remember(context) { mutableStateOf<List<Notice>?>(null) }
    LaunchedEffect(context) {
        notices = withContext(Dispatchers.IO) {
            try {
                val json = context.assets.open("cyclon/notices.json").bufferedReader().use { JSONArray(it.readText()) }
                List(json.length()) { index ->
                    val entry = json.getJSONObject(index)
                    Notice(entry.getString("title"), entry.getString("license"), entry.getString("text"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }
    var showingLicenses by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = notices?.firstOrNull { it.title == selectedTitle }
    var selectedPage by rememberSaveable(selectedTitle) { mutableStateOf(0) }
    val pages = remember(selected) { selected?.let { noticePages(it.text) } ?: emptyList() }
    val page = selectedPage.coerceIn(0, maxOf(0, pages.lastIndex))
    val view = LocalView.current
    LaunchedEffect(showingLicenses, selectedTitle, query, page) {
        val scroll = generateSequence(view.parent) { it.parent }
            .filterIsInstance<androidx.core.widget.NestedScrollView>().firstOrNull()
        scroll?.post { scroll.scrollTo(0, 0) }
    }
    BackHandler(showingLicenses) {
        if (selectedTitle != null) selectedTitle = null else showingLicenses = false
    }
    val paper = colorResource(R.color.cyclon_paper)
    val ink = colorResource(R.color.cyclon_ink)
    val muted = colorResource(R.color.cyclon_muted)
    CyclonTheme {
        // The settings host owns vertical scrolling; a nested lazy/scroll container gets
        // unbounded height here. Keep this column wrap-content and bound each text page.
        Column(Modifier.fillMaxWidth().background(paper).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                selected != null -> {
                    TextButton(onClick = { selectedTitle = null }) { Text(stringResource(R.string.cyclon_back)) }
                    Text(selected.title, style = MaterialTheme.typography.titleMedium)
                    Text(selected.license, color = muted)
                    NoticePageNavigation(page, pages.size) { selectedPage = it }
                    SelectionContainer { Text(pages[page], style = MaterialTheme.typography.bodyMedium) }
                    NoticePageNavigation(page, pages.size) { selectedPage = it }
                }
                showingLicenses -> {
                    TextButton(onClick = { showingLicenses = false }) { Text(stringResource(R.string.cyclon_back)) }
                    Text(stringResource(R.string.cyclon_notices), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.cyclon_search_licenses)) }, modifier = Modifier.fillMaxWidth()
                    )
                    when {
                        notices == null -> Text(stringResource(R.string.cyclon_loading))
                        notices!!.isEmpty() -> Text(stringResource(R.string.cyclon_licenses_unavailable))
                        else -> {
                            val results = notices!!.filter { it.title.contains(query, true) || it.license.contains(query, true) }
                            if (results.isEmpty()) Text(stringResource(R.string.cyclon_no_results))
                            results.forEach { notice ->
                                Column(Modifier.fillMaxWidth().clickable { selectedTitle = notice.title }.padding(vertical = 12.dp)) {
                                    Text(notice.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(notice.license, color = muted, style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                else -> {
                    CyclonSymbol(ink, Modifier.size(64.dp))
                    Text(stringResource(R.string.cyclon_services_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.cyclon_based_on_microg), color = muted)
                    Text(stringResource(R.string.cyclon_about_description))
                    Text(stringResource(R.string.cyclon_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = muted)
                    HorizontalDivider()
                    Text(stringResource(R.string.cyclon_credits), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { showingLicenses = true }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.cyclon_notices))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticePageNavigation(page: Int, count: Int, onPage: (Int) -> Unit) {
    if (count <= 1) return
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cyclon_notice_page, page + 1, count), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) {
                Text(stringResource(R.string.cyclon_previous_page))
            }
            TextButton(onClick = { onPage(page + 1) }, enabled = page < count - 1) {
                Text(stringResource(R.string.cyclon_next_page))
            }
        }
    }
}
