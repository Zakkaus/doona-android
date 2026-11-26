package dev.doona.android.feature.subscriptions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription

/**
 * A subscription's page: which sheet is open, if any, the name or User-Agent being typed into it, whether the core
 * refused the last rename ([renameFailed], shown under the field until the name changes), and whether it refused the
 * last delete ([deleteFailed], a banner over the page until the next try).
 */
@Immutable
internal data class RvSubDetailState(
    val sheet: RvSubSheet? = null,
    val name: String = "",
    val userAgent: String = "",
    val renameFailed: Boolean = false,
    val deleteFailed: Boolean = false,
)

/**
 * 自動更新 on a subscription's page, as the app's settings give it: [Unread] until they answer (the row has no value
 * yet, never a guess), [Off] (關閉, as 設定 says it), or every [Every.hours] hours.
 */
@Immutable
internal sealed interface RvAutoUpdate {
    data object Unread : RvAutoUpdate

    data object Off : RvAutoUpdate

    data class Every(val hours: Int) : RvAutoUpdate
}

/** The settings' interval in [hours] as the row shows it: null while auto-update is off. */
internal fun rvAutoUpdate(hours: Int?): RvAutoUpdate = hours?.let(RvAutoUpdate::Every) ?: RvAutoUpdate.Off

internal enum class RvSubSheet { Rename, UserAgent, Delete }

/**
 * A subscription's page, pushed from 訂閱: the name as the large title; its key stats (nodes, used of its quota, end
 * date, last update, format); 更新 with the auto-update interval and the subscription's own User-Agent (預設 until it
 * has one; a sheet with a field, as 重新命名, opens on it and saves it to this subscription through [onUserAgent], and
 * an empty field goes back to the default, null); 訂閱 with the name
 * (重新命名 in a sheet with a field) and the link's host; then 刪除訂閱 alone in its card, its glyph in the error hue,
 * asking in the confirm sheet. 立即更新 floats at the bottom. The interval reads [autoUpdate], and its row runs
 * [onAutoUpdate]. A saved name goes to [onRename], a confirmed
 * delete to [onDelete].
 */
@Composable
internal fun RvSubDetail(
    sub: RvSubscription,
    state: RvSubDetailState,
    onChange: (RvSubDetailState) -> Unit,
    onBack: () -> Unit,
    onUpdate: () -> Unit,
    onUserAgent: (String?) -> Unit = {},
    autoUpdate: RvAutoUpdate,
    onAutoUpdate: () -> Unit,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(
        sub.name,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_sub_update_now), onUpdate) },
    ) { titleBlock ->
        RvLargeTitle(sub.name, titleBlock, insets)
        RvGutter(banner = if (state.deleteFailed) rvDeleteFailedBanner(sub, onDelete) else null) { KeyStats(sub) }
        RvSectionTitle(stringResource(R.string.rv_sub_updates), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { UpdatesCard(sub.userAgent, autoUpdate, onAutoUpdate) { onChange(state.copy(sheet = RvSubSheet.UserAgent, userAgent = sub.userAgent.orEmpty())) } }
        RvSectionTitle(stringResource(R.string.rv_sub_section), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvCard {
                RvRow({ RvIconDisc(R.drawable.rv_sym_edit) }, stringResource(R.string.rv_sub_name), sub.name, onClick = { onChange(state.copy(sheet = RvSubSheet.Rename, name = sub.name)) }, subtitleKeepWords = false) { RvChevron() }
                RvRow({ RvIconDisc(R.drawable.rv_sym_link) }, stringResource(R.string.rv_sub_link), sub.host, subtitleKeepWords = false)
            }
            RvCard {
                RvRow({ RvIconDisc(R.drawable.rv_sym_delete, Rv.colors.error) }, stringResource(R.string.rv_sub_delete), null, onClick = { onChange(state.copy(sheet = RvSubSheet.Delete)) })
            }
        }
    }
    val close = { onChange(state.copy(sheet = null)) }
    RvConfirmSheet(
        visible = state.sheet == RvSubSheet.Rename,
        title = stringResource(R.string.rv_sub_rename),
        body = null,
        confirm = stringResource(R.string.rv_save),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            onRename(state.name.trim())
            close()
        },
        onDismiss = close,
        confirmEnabled = state.name.isNotBlank() && rvNameValid(state.name),
    ) { RvRenameField(state.name, state.renameFailed) { onChange(state.copy(name = it, renameFailed = false)) } }
    RvConfirmSheet(
        visible = state.sheet == RvSubSheet.UserAgent,
        title = stringResource(R.string.rv_sub_user_agent),
        body = null,
        confirm = stringResource(R.string.rv_save),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            onUserAgent(rvAgentOf(state.userAgent))
            close()
        },
        onDismiss = close,
        confirmEnabled = rvAgentValid(state.userAgent),
    ) { RvUserAgentField(state.userAgent, { onChange(state.copy(userAgent = it)) }) }
    RvConfirmSheet(
        visible = state.sheet == RvSubSheet.Delete,
        title = stringResource(R.string.rv_sub_delete_title, sub.name),
        body = pluralStringResource(R.plurals.rv_sub_delete_body, sub.nodes, sub.nodes),
        confirm = stringResource(R.string.rv_menu_delete),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            close()
            onDelete()
        },
        onDismiss = close,
        titleKeepWords = false,
    )
}

/**
 * The rename sheet's field: the sheet's title names it, so it has no label of its own (Addendum 3). A name the core
 * would refuse shows the inline error, as does a rename the core refused ([failed]).
 */
@Composable
internal fun RvRenameField(name: String, failed: Boolean = false, onName: (String) -> Unit) {
    val error = when {
        !rvNameValid(name) -> stringResource(R.string.rv_sub_name_invalid)
        failed -> stringResource(R.string.rv_sub_rename_failed)
        else -> null
    }
    RvTextField(name, onName, label = null, name = stringResource(R.string.rv_sub_rename), error = error)
}

/** Whether the core takes [name] (`Engine.renameSubscription`): at most [MAX_NAME] code points, none a control or line break. */
internal fun rvNameValid(name: String): Boolean =
    name.codePointCount(0, name.length) <= MAX_NAME &&
        name.none { Character.isISOControl(it) || it == LINE_SEPARATOR || it == PARAGRAPH_SEPARATOR }

private const val MAX_NAME = 64
private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'

/**
 * A subscription's User-Agent field, in the User-Agent sheet (named by its title) and on 新增訂閱 (named by [label]).
 * Empty while the subscription sends the default, which it shows as its placeholder: the value doona sends, not a hint.
 * A value the core would refuse shows the inline error.
 */
@Composable
internal fun RvUserAgentField(userAgent: String, onUserAgent: (String) -> Unit, label: String? = null, enabled: Boolean = true) {
    val default = stringResource(R.string.rv_sub_user_agent_value, stringResource(R.string.rv_settings_version_value))
    val error = if (rvAgentValid(userAgent)) null else stringResource(R.string.rv_sub_user_agent_invalid)
    RvTextField(userAgent, onUserAgent, label, name = stringResource(R.string.rv_sub_user_agent), placeholder = default, error = error, enabled = enabled)
}

/** Whether [agent] can be saved, by the core's rule: empty or blank for the default, else 256 printable ASCII characters at most. */
internal fun rvAgentValid(agent: String) = agent.length <= MAX_AGENT && agent.all { it in ' '..'~' }

/** What a valid typed User-Agent saves: null for the default, else the value trimmed, as the core keeps it. */
internal fun rvAgentOf(agent: String): String? = agent.trim().ifEmpty { null }

private const val MAX_AGENT = 256

/** Revolut's Key stats card (measured §11): what the subscription holds and when it was last fetched. */
@Composable
private fun KeyStats(sub: RvSubscription) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    // A provider that sends no quota has no use to show against it.
    val used = if (sub.hasQuota) stringResource(R.string.rv_subs_used, format.usedOf(sub.usedBytes, total), total.toString()) else RV_NO_VALUE
    RvCard {
        RvStatRow(stringResource(R.string.rv_sub_node_count), sub.nodes.toString())
        RvStatRow(stringResource(R.string.rv_sub_used), used)
        RvStatRow(stringResource(R.string.rv_sub_expires), sub.expiresAtEpochMs?.let { format.date(it) } ?: stringResource(R.string.rv_subs_no_expiry))
        // A subscription never fetched has neither yet.
        RvStatRow(stringResource(R.string.rv_sub_updated), sub.updatedAtEpochMs?.let(format::time) ?: RV_NO_VALUE)
        RvStatRow(stringResource(R.string.rv_sub_format), sub.format?.let { stringResource(it.label) } ?: RV_NO_VALUE)
    }
}

/**
 * The auto-update interval ([autoUpdate]; an empty line until it is read), whose row runs [onAutoUpdate], and the subscription's
 * [userAgent] (預設 while null), whose row opens its sheet ([onUserAgent]).
 */
@Composable
private fun UpdatesCard(userAgent: String?, autoUpdate: RvAutoUpdate, onAutoUpdate: () -> Unit, onUserAgent: () -> Unit) {
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_schedule) },
            stringResource(R.string.rv_sub_auto_update),
            when (autoUpdate) {
                // An empty line keeps the row's two-line height, so nothing moves when the settings answer.
                RvAutoUpdate.Unread -> ""

                RvAutoUpdate.Off -> stringResource(R.string.ui_off)

                is RvAutoUpdate.Every -> pluralStringResource(R.plurals.rv_interval_hours, autoUpdate.hours, autoUpdate.hours)
            },
            onClick = onAutoUpdate,
        ) { RvChevron() }
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_dns) },
            stringResource(R.string.rv_sub_user_agent),
            userAgent ?: stringResource(R.string.rv_sub_user_agent_default),
            onClick = onUserAgent,
            subtitleKeepWords = false,
        ) { RvChevron() }
    }
}

@get:StringRes
internal val RvSubFormat.label: Int
    get() = when (this) {
        RvSubFormat.Clash -> R.string.rv_sub_format_clash
        RvSubFormat.SingBox -> R.string.rv_sub_format_sing_box
        RvSubFormat.Sip008 -> R.string.rv_sub_format_sip008
        RvSubFormat.Links -> R.string.rv_sub_format_links
    }
