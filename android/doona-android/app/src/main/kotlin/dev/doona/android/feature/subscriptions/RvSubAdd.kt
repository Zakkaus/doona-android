package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_LINK_PLACEHOLDER
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvProgress
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvSubscription

/** Why an import failed, as the core tells them apart: no answer, not a subscription, over the 8 MiB bound. */
internal enum class RvAddFailure { Unreachable, NotSubscription, TooLarge }

/** Where an import stands: being typed, downloading, done with its subscription, or failed. */
@Immutable
internal sealed interface RvAddPhase {
    data object Editing : RvAddPhase

    /** Downloading; a provider that gives no length leaves [fraction] null, and the core reports no byte count at all ([downloadedBytes] null). */
    data class Importing(val downloadedBytes: Long?, val fraction: Float?) : RvAddPhase

    data class Done(val sub: RvSubscription) : RvAddPhase

    data class Failed(val failure: RvAddFailure) : RvAddPhase
}

/**
 * The add page's state: the link and the optional name and User-Agent typed so far, whether the clipboard's description
 * says it holds a link (read from the description only, so Android shows no paste notice until the row is tapped), and
 * the phase. The local network prompt a link on the local network raises at 匯入 is the app's, shown over every page.
 */
@Immutable
internal data class RvAddState(
    val link: String = "",
    val name: String = "",
    val userAgent: String = "",
    val clipboardHasLink: Boolean = true,
    val phase: RvAddPhase = RvAddPhase.Editing,
)

/**
 * 新增訂閱, a pushed page with the large title, which collapses onto the bar as the page scrolls. While nothing is typed and the clipboard holds a link, a row offers it (the content
 * is read only when the row is tapped). Then the link, an optional name and an optional User-Agent (empty for the
 * default, which is its placeholder; a value the core would refuse shows the inline error and holds 匯入), and 匯入.
 * While importing, the fields rest
 * and a card shows the download over the bar; when done, a card says what came in (nodes, format, protocols) and 完成
 * returns; when it fails, the banner gives the reason and 重試, and the fields keep what was typed. [protocols] counts
 * what came in by protocol, most nodes first.
 */
@Composable
internal fun RvSubAdd(
    state: RvAddState,
    onChange: (RvAddState) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    protocols: List<RvProtocolCount>,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val phase = state.phase
    RvScrollPage(
        stringResource(R.string.rv_add_subscription),
        insets,
        onBack = onBack,
        bottomAction = {
            if (phase is RvAddPhase.Done) {
                RvPrimaryButton(stringResource(R.string.rv_done), onBack)
            } else {
                val ready = state.link.startsWith(HTTP) && rvAgentValid(state.userAgent)
                RvPrimaryButton(stringResource(R.string.rv_add_import), onImport, enabled = phase !is RvAddPhase.Importing && ready)
            }
        },
    ) { titleBlock ->
        RvLargeTitle(stringResource(R.string.rv_add_subscription), titleBlock, insets)
        RvGutter(banner = (phase as? RvAddPhase.Failed)?.let { failure(it.failure, onImport) }) {
            if (phase is RvAddPhase.Done) Imported(phase.sub, protocols) else Form(state, onChange, onPaste)
        }
    }
}

@Composable
private fun Form(state: RvAddState, onChange: (RvAddState) -> Unit, onPaste: () -> Unit) {
    val phase = state.phase
    val editable = phase !is RvAddPhase.Importing
    if (state.clipboardHasLink && state.link.isEmpty()) {
        RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_content_paste) }, stringResource(R.string.rv_add_clipboard), null, onClick = onPaste) }
    }
    Column(Modifier.padding(top = RvSpace.tight), verticalArrangement = Arrangement.spacedBy(RvSpace.chartGap)) {
        RvTextField(
            state.link,
            { onChange(state.copy(link = it.trim())) },
            stringResource(R.string.rv_add_link),
            placeholder = RV_LINK_PLACEHOLDER,
            enabled = editable,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        RvTextField(state.name, { onChange(state.copy(name = it)) }, stringResource(R.string.rv_add_name), enabled = editable)
        RvUserAgentField(state.userAgent, { onChange(state.copy(userAgent = it)) }, stringResource(R.string.rv_add_user_agent), editable)
    }
    if (phase is RvAddPhase.Importing) Progress(phase)
}

/**
 * The download so far over the bar: a 52 dp row, then the bar on the card's padding. With no length there is no bar to
 * fill: the row carries the busy bar in its trailing slot, as 訂閱's row does while it updates, so a frame of it cannot
 * be read as a share. With no byte count the second line stays and shows a dash in its place.
 */
@Composable
private fun Progress(phase: RvAddPhase.Importing) {
    val format = rememberRvFormat()
    val bar = Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)
    val busy: (@Composable () -> Unit)? = if (phase.fraction == null) ({ RvBusy(Modifier.width(RvSize.busy)) }) else null
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_stats_ic_sync) },
            stringResource(R.string.rv_add_importing),
            stringResource(R.string.rv_add_downloaded, phase.downloadedBytes?.let { format.bytes(it).toString() } ?: RV_NO_VALUE),
            trailing = busy,
        )
        phase.fraction?.let { RvProgress(it, bar) }
    }
}

/** A protocol and how many nodes of what came in speak it. */
@Immutable
internal data class RvProtocolCount(val protocol: String, val nodes: Int)

/**
 * What came in: the subscription with its node count and the format found, then a stat row per protocol in the order
 * given, as Revolut's Key stats.
 */
@Composable
private fun Imported(sub: RvSubscription, protocols: List<RvProtocolCount>) {
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_nodes_ic_check_circle, Rv.colors.good) },
            stringResource(R.string.rv_add_done, sub.name),
            pluralStringResource(R.plurals.rv_nodes_nodes, sub.nodes, sub.nodes) + SEPARATOR + stringResource(sub.format.label),
            titleKeepWords = false,
        )
        protocols.forEach { (protocol, count) -> RvStatRow(protocol, pluralStringResource(R.plurals.rv_nodes_nodes, count, count)) }
    }
}

/** A failed import, an error, over the form: why, and 重試 importing again. */
@Composable
private fun failure(failure: RvAddFailure, onRetry: () -> Unit): RvNotice {
    val reason = when (failure) {
        RvAddFailure.Unreachable -> stringResource(R.string.rv_add_unreachable)
        RvAddFailure.NotSubscription -> stringResource(R.string.rv_add_not_subscription_reason)
        RvAddFailure.TooLarge -> stringResource(R.string.rv_add_too_large, RV_MAX_BODY)
    }
    return RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_add_failed), reason, stringResource(R.string.rv_retry), onRetry)
}

private const val HTTP = "http"

/** The core's download bound (`fetch.rs` `MAX_BODY`, 8 << 20). */
internal const val RV_MAX_BODY = "8 MiB"
