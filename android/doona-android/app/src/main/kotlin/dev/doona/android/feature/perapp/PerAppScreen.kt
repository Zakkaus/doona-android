package dev.doona.android.feature.perapp

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaAppIcon
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaCheckRow
import dev.doona.android.designsystem.components.DoonaEmptyState
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaLazyPageSheet
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaSearchField
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.DoonaTopBar
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.labelRes
import dev.doona.android.feature.messageRes

/** The 依 App entry: builds the ViewModel and collects its state while the screen is started. */
@Composable
fun PerAppRoute(engine: Engine, installedApps: InstalledApps, onBack: () -> Unit) {
    val viewModel: PerAppViewModel = viewModel(factory = PerAppViewModel.factory(engine, installedApps))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The first resume reads the device's apps; each later one reads them again, since an app may have come or gone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(PerAppAction.Resume) }
    PerAppScreen(state, viewModel::onAction, onBack)
}

@get:StringRes
private val AppPolicy.summary: Int
    get() = when (this) {
        AppPolicy.All -> R.string.perapp_summary_all
        AppPolicy.OnlyListed -> R.string.perapp_summary_only
        AppPolicy.ExceptListed -> R.string.perapp_summary_except
    }

/**
 * 依 App: which apps use the tunnel. The policy as a segmented control, a note on what saving does, and, unless every
 * app uses the tunnel, the search field and the apps of the device to check. The page is a lazy list, since the apps of
 * a device run to a few hundred; the controls are its first item and scroll away with it.
 */
@Composable
fun PerAppScreen(state: PerAppUiState, onAction: (PerAppAction) -> Unit, onBack: () -> Unit) {
    val search = rememberTextFieldState(state.query)
    val latest by rememberUpdatedState(onAction)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect { latest(PerAppAction.SetQuery(it)) } }
    Column(Modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
        DoonaTopBar(onBack)
        DoonaLazyPageSheet(Modifier.weight(1f)) {
            item(key = HEADER, contentType = HEADER) { Controls(state, search, onAction) }
            if (state.policyLoaded && state.policy != AppPolicy.All) appList(state, onAction)
        }
    }
}

private const val HEADER = "header"

/** The title, the policy, what it means and what saving does, then the search field when there is a list below. */
@Composable
private fun Controls(state: PerAppUiState, search: TextFieldState, onAction: (PerAppAction) -> Unit) {
    val space = DoonaTheme.spacing
    val c = DoonaTheme.colors
    Column(Modifier.fillMaxWidth()) {
        DoonaPageTitle(stringResource(R.string.perapp_title))
        DoonaGap(space.afterTitle)
        // What the controls would show before the saved policy is read is a placeholder, so they wait for it.
        if (!state.policyLoaded) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { DoonaProgress() }
            return
        }
        Failures(state, onAction)
        DoonaSegmented(
            AppPolicy.entries.map { stringResource(it.labelRes()) },
            selected = state.policy.ordinal,
            onSelect = { onAction(PerAppAction.SetPolicy(AppPolicy.entries[it])) },
        )
        DoonaGap(space.block)
        DoonaText(stringResource(state.policy.summary), style = DoonaTheme.type.caption, color = c.text.secondary)
        if (state.needsApp) {
            DoonaGap(space.item)
            DoonaText(stringResource(R.string.perapp_needs_app), style = DoonaTheme.type.fieldError, color = c.text.negative)
        }
        DoonaGap(space.section)
        DoonaButton(stringResource(R.string.perapp_save), { onAction(PerAppAction.Save) }, enabled = state.canSave, busy = state.busy)
        DoonaGap(space.item)
        DoonaText(stringResource(R.string.perapp_reconnect_note), style = DoonaTheme.type.caption, color = c.text.secondary)
        if (state.policy != AppPolicy.All) {
            DoonaGap(space.section)
            DoonaSearchField(search, stringResource(R.string.perapp_search))
            DoonaGap(space.block)
        }
    }
}

/** A failed write says so by its code; a save that was stored but whose tunnel did not come up says why, as stored. */
@Composable
private fun Failures(state: PerAppUiState, onAction: (PerAppAction) -> Unit) {
    val dismiss = stringResource(R.string.ui_dismiss)
    val messages = listOfNotNull(
        state.error?.let { stringResource(it.messageRes()) },
        state.rebuildFailure?.let { stringResource(R.string.perapp_rebuild_failed, stringResource(it.messageRes())) },
    )
    for (message in messages) {
        DoonaErrorBanner(message, dismiss, { onAction(PerAppAction.DismissError) })
        DoonaGap(DoonaTheme.spacing.block)
    }
}

/** The rows below the controls: the apps, or the one thing that stands in for them. */
private fun LazyListScope.appList(state: PerAppUiState, onAction: (PerAppAction) -> Unit) {
    when {
        !state.loaded -> item(key = LOADING, contentType = LOADING) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                DoonaGap(DoonaTheme.spacing.section)
                DoonaProgress()
            }
        }

        state.apps.isEmpty() -> item(key = EMPTY, contentType = EMPTY) {
            if (state.hasApps) {
                DoonaEmptyState(R.drawable.ic_search, stringResource(R.string.perapp_no_match), stringResource(R.string.perapp_no_match_line))
            } else {
                DoonaEmptyState(R.drawable.ic_filter, stringResource(R.string.perapp_no_apps), stringResource(R.string.perapp_no_apps_line))
            }
        }

        else -> items(state.apps, key = { it.packageName }, contentType = { APP }) { app ->
            DoonaCheckRow(
                checked = app.packageName in state.checked,
                onChange = { onAction(PerAppAction.Toggle(app.packageName, it)) },
                title = app.label,
                subtitle = app.packageName,
                leading = { DoonaAppIcon(app.packageName) },
            )
        }
    }
}

private const val LOADING = "loading"
private const val EMPTY = "empty"
private const val APP = "app"
