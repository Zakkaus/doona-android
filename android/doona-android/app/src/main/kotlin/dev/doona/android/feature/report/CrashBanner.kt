package dev.doona.android.feature.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.theme.DoonaMotion
import dev.doona.android.report.Reports

/**
 * The offer to report a crash ([Reports.crashOffered]), [shown] while one waits: [onExport] exports the report,
 * [onDismiss] takes the offer down, and [onShown] tells the offer it reached the screen.
 */
@Immutable
class CrashNotice(val shown: Boolean, val onExport: () -> Unit, val onDismiss: () -> Unit, val onShown: () -> Unit = {}) {
    companion object {
        val None = CrashNotice(shown = false, onExport = {}, onDismiss = {})
    }
}

/**
 * The crash offer of [reports], for the 活動 tab whichever page it shows. Its action starts the export and opens the
 * 回報問題 page ([onOpenReport]), where the export's progress and any failure show.
 */
@Composable
fun crashNotice(reports: Reports, onOpenReport: () -> Unit): CrashNotice {
    val offered by reports.crashOffered.collectAsStateWithLifecycle()
    return CrashNotice(
        shown = offered,
        onExport = {
            reports.startExport()
            onOpenReport()
        },
        onDismiss = reports::dismissCrash,
        onShown = reports::crashShown,
    )
}

/**
 * The crash offer at the top of a page; it comes and goes with the popup's motion, so the cards below slide rather than
 * jump. Once drawn, it tells the offer so, which then is not made again.
 */
@Composable
fun CrashBanner(crash: CrashNotice) {
    val motion = tween<Float>(DoonaMotion.POPUP_MS, easing = DoonaMotion.emphasizedDecelerate)
    val size = tween<IntSize>(DoonaMotion.POPUP_MS, easing = DoonaMotion.emphasizedDecelerate)
    AnimatedVisibility(
        visible = crash.shown,
        enter = fadeIn(motion) + expandVertically(size),
        exit = fadeOut(motion) + shrinkVertically(size),
    ) {
        LaunchedEffect(Unit) { crash.onShown() }
        DoonaErrorBanner(
            stringResource(R.string.report_crash_banner),
            stringResource(R.string.ui_dismiss),
            onDismiss = crash.onDismiss,
            actionLabel = stringResource(R.string.report_crash_action),
            onAction = crash.onExport,
        )
    }
}
