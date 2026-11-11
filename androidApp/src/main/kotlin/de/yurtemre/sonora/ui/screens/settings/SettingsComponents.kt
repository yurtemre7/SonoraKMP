package de.yurtemre.sonora.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.yurtemre.sonora.R
import de.yurtemre.sonora.ui.components.SectionHeader

/** Max content width for settings on wide windows (UI-34). */
private val SettingsMaxWidth = 840.dp

/** Shared settings scaffold: large flexible app bar that collapses on scroll, flat list body. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    topBarBottom: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.nav_back))
                        }
                    },
                    scrollBehavior = scroll,
                )
                topBarBottom()
            }
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = SettingsMaxWidth),
                contentPadding = PaddingValues(bottom = 32.dp),
                content = content,
            )
        }
    }
}

/** Sentence-case section header item with a little top breathing room. */
fun LazyListScope.settingsHeader(key: String, text: @Composable () -> String) {
    item(key = "h_$key", contentType = "header") {
        SectionHeader(text(), Modifier.padding(top = 12.dp))
    }
}

fun LazyListScope.settingsDivider(key: String) {
    item(key = "d_$key", contentType = "divider") {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Flat row: leading icon, title, optional subtitle, trailing slot. Navigation rows get a chevron. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = cs.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Switch row; the whole row toggles, with a haptic tick (merged semantics, Role.Switch). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        enabled = enabled,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onCheckedChange(it)
        },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Row whose current value opens a dropdown of [options]. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val current = label(selected)
    Box {
        SettingsRow(
            title = title,
            icon = icon,
            subtitle = subtitle,
            onClick = { open = true },
            modifier = Modifier.semantics { stateDescription = current },
            trailing = {
                Text(current, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            },
        )
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    trailingIcon = { if (option == selected) Icon(Icons.Rounded.Check, null) },
                    onClick = {
                        open = false
                        if (option != selected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(option)
                        }
                    },
                )
            }
        }
    }
}

/** M3 Expressive connected toggle group (single choice). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    enabled: (T) -> Boolean = { true },
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    if (option != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                enabled = enabled(option),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (icon != null) {
                    Icon(icon(option), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Body text paragraph inside settings lists. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
