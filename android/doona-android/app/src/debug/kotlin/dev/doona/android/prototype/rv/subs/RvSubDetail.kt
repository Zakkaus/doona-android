package dev.doona.android.prototype.rv.subs

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
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvConfirmSheet
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvTextField
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSubFormat
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.rememberRvFormat

/** A subscription's page: which sheet is open, if any, and the name or User-Agent being typed into it. */
@Immutable
internal data class RvSubDetailState(val sheet: RvSubSheet? = null, val name: String = "", val userAgent: String = "")

internal enum class RvSubSheet { Rename, UserAgent, Delete }

/**
 * A subscription's page, pushed from 訂閱: the name as the large title; its key stats (nodes, used of its quota, end
 * date, last update, format); 更新 with the auto-update interval and the subscription's own User-Agent (預設 until it
 * has one; a sheet with a field, as 重新命名, sets it, and an empty field goes back to the default); 訂閱 with the name
 * (重新命名 in a sheet with a field) and the link's host; then 刪除訂閱 alone in its card, its glyph in the error hue,
 * asking in the confirm sheet. 立即更新 floats at the bottom.
 */
@Composable
internal fun RvSubDetail(sub: RvSubscription, state: RvSubDetailState, onChange: (RvSubDetailState) -> Unit, onBack: () -> Unit, onUpdate: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(
        sub.name,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_sub_update_now), onUpdate) },
    ) { titleBlock ->
        RvLargeTitle(sub.name, titleBlock, insets)
        RvGutter { KeyStats(sub) }
        RvSectionTitle(stringResource(R.string.rv_sub_updates), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { UpdatesCard { onChange(state.copy(sheet = RvSubSheet.UserAgent, userAgent = "")) } }
        RvSectionTitle(stringResource(R.string.rv_sub_section), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvCard {
                RvRow({ RvIconDisc(R.drawable.rv_sym_edit) }, stringResource(R.string.rv_sub_name), sub.name, onClick = { onChange(state.copy(sheet = RvSubSheet.Rename, name = sub.name)) }) { RvChevron() }
                RvRow({ RvIconDisc(R.drawable.rv_sym_link) }, stringResource(R.string.rv_sub_link), sub.host)
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
        onConfirm = close,
        onDismiss = close,
        confirmEnabled = state.name.isNotBlank(),
    ) { RvRenameField(state.name) { onChange(state.copy(name = it)) } }
    RvConfirmSheet(
        visible = state.sheet == RvSubSheet.UserAgent,
        title = stringResource(R.string.rv_sub_user_agent),
        body = null,
        confirm = stringResource(R.string.rv_save),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = close,
        onDismiss = close,
    ) { RvUserAgentField(state.userAgent) { onChange(state.copy(userAgent = it)) } }
    RvConfirmSheet(
        visible = state.sheet == RvSubSheet.Delete,
        title = stringResource(R.string.rv_sub_delete_title, sub.name),
        body = pluralStringResource(R.plurals.rv_sub_delete_body, sub.nodes, sub.nodes),
        confirm = stringResource(R.string.rv_menu_delete),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = close,
        onDismiss = close,
    )
}

/** The rename sheet's field: the sheet's title names it, so it has no label of its own (Addendum 3). */
@Composable
internal fun RvRenameField(name: String, onName: (String) -> Unit) {
    RvTextField(name, onName, label = null, name = stringResource(R.string.rv_sub_rename))
}

/**
 * The User-Agent sheet's field, named by the sheet's title. Empty while the subscription sends the default, which it
 * shows as its placeholder: the value doona sends, not a hint.
 */
@Composable
internal fun RvUserAgentField(userAgent: String, onUserAgent: (String) -> Unit) {
    val default = stringResource(R.string.rv_sub_user_agent_value, stringResource(R.string.rv_settings_version_value))
    RvTextField(userAgent, onUserAgent, label = null, name = stringResource(R.string.rv_sub_user_agent), placeholder = default)
}

/** Revolut's Key stats card (measured §11): what the subscription holds and when it was last fetched. */
@Composable
private fun KeyStats(sub: RvSubscription) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    RvCard {
        RvStatRow(stringResource(R.string.rv_sub_node_count), sub.nodes.toString())
        RvStatRow(stringResource(R.string.rv_sub_used), stringResource(R.string.rv_subs_used, format.bytesIn(sub.usedBytes, total), total.toString()))
        RvStatRow(stringResource(R.string.rv_sub_expires), sub.expiresAtEpochMs?.let { format.date(it) } ?: stringResource(R.string.rv_subs_no_expiry))
        RvStatRow(stringResource(R.string.rv_sub_updated), format.time(sub.updatedAtEpochMs))
        RvStatRow(stringResource(R.string.rv_sub_format), stringResource(sub.format.label))
    }
}

/** The auto-update interval, and the User-Agent, whose row opens its sheet ([onUserAgent]). */
@Composable
private fun UpdatesCard(onUserAgent: () -> Unit) {
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_schedule) },
            stringResource(R.string.rv_sub_auto_update),
            stringResource(R.string.rv_settings_update_interval_value, RvData.UPDATE_HOURS),
            onClick = NOT_WIRED,
        ) { RvChevron() }
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_dns) },
            stringResource(R.string.rv_sub_user_agent),
            stringResource(R.string.rv_sub_user_agent_default),
            onClick = onUserAgent,
        ) { RvChevron() }
    }
}

@get:StringRes
internal val RvSubFormat.label: Int
    get() = when (this) {
        RvSubFormat.Clash -> R.string.rv_sub_format_clash
        RvSubFormat.SingBox -> R.string.rv_sub_format_sing_box
        RvSubFormat.Links -> R.string.rv_sub_format_links
    }
