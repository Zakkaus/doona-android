package dev.tunbench.preview.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.tunbench.preview.R
import dev.tunbench.preview.model.ControlState
import dev.tunbench.preview.theme.HomeDims
import dev.tunbench.preview.theme.HomeType

enum class Destination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    Control(R.string.nav_control, R.drawable.speed_24px, R.drawable.speed_fill1_24px),
    Observe(R.string.nav_observe, R.drawable.lan_24px, R.drawable.lan_fill1_24px),
    Bench(R.string.nav_bench, R.drawable.science_24px, R.drawable.science_fill1_24px),
}

/**
 * Root pages in Google Home's frame: the tinted top zone with the title and the menu in Home's white
 * circle button, the page on surface, and the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(state: ControlState, modifier: Modifier = Modifier, initialSettingsOpen: Boolean = false) {
    var current by rememberSaveable { mutableStateOf(Destination.Control) }
    var settingsOpen by rememberSaveable { mutableStateOf(initialSettingsOpen) }
    if (settingsOpen) {
        SettingsScreen(state, onBack = { settingsOpen = false }, modifier = modifier)
        return
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val zone = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current.label), style = HomeType.title) },
                actions = { OverflowMenu(onSettings = { settingsOpen = true }) },
                expandedHeight = HomeDims.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = zone, scrolledContainerColor = zone),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            ShortNavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = destination == current
                    ShortNavigationBarItem(
                        selected = selected,
                        onClick = { current = destination },
                        icon = {
                            Icon(
                                painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
        when (current) {
            Destination.Control -> ControlScreen(state, onOpenSettings = { settingsOpen = true }, modifier = content)
            else -> PlaceholderScreen(stringResource(current.label), content)
        }
    }
}

/** Home's white circle button with a tooltip, opening a menu with Settings, as in the library's MenuSample. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowMenu(onSettings: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val more = stringResource(R.string.action_more)
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(more) } },
            state = rememberTooltipState(),
        ) {
            FilledIconButton(
                onClick = { expanded = true },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(painter = painterResource(R.drawable.more_vert_24px), contentDescription = more)
            }
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSettings()
                    },
                    text = { Text(stringResource(R.string.action_settings)) },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.settings_24px),
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                )
            }
        }
    }
}
