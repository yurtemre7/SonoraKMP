package de.yurtemre.sonora.ui.screens.licenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Licenses (UI-37): bundled font (OFL) + AboutLibraries list generated at build time. */
@Composable
fun LicensesRoute(onBack: () -> Unit) {
    val libs by produceLibraries(R.raw.aboutlibraries)
    var showOfl by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        val l = libs
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { LoadingIndicator() }
        } else {
            LibrariesContainer(
                libraries = l,
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                header = {
                    item(key = "fonts-header") { SectionHeader(stringResource(R.string.info2_licenses_fonts)) }
                    item(key = "font-bricolage") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.info2_font_name)) },
                            supportingContent = { Text(stringResource(R.string.info2_font_license)) },
                            leadingContent = { Icon(Icons.Rounded.FontDownload, null) },
                            modifier = Modifier.clickable { showOfl = true },
                        )
                    }
                    item(key = "libs-header") { SectionHeader(stringResource(R.string.info2_licenses_libraries)) }
                },
            )
        }
    }
    if (showOfl) OflDialog { showOfl = false }
}

@Composable
private fun OflDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("OFL-BricolageGrotesque.txt").bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info2_font_license)) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.info2_close)) } },
    )
}
