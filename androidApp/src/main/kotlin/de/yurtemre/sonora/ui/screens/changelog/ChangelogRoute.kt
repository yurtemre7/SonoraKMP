package de.yurtemre.sonora.ui.screens.changelog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Changelog (UI-36): renders the bundled `assets/CHANGELOG.md` offline. */
@Composable
fun ChangelogRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    // null = loading, "" = missing
    val markdown by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val md = markdown
        when {
            md == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { LoadingIndicator() }
            md.isBlank() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                EmptyState(icon = Icons.Rounded.History, title = stringResource(R.string.info2_changelog_missing))
            }
            else -> SleeveMarkdown(
                // Drop the document title; the app bar already says "Changelog".
                content = md.lineSequence().dropWhile { it.isBlank() || it.startsWith("# ") }.joinToString("\n"),
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
