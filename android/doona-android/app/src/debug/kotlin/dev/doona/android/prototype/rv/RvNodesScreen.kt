package dev.doona.android.prototype.rv

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Subscription
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What the node screen shows: one group, the subscriptions its nodes come from, and each node's protocol by node id,
 * which the core's Member does not carry yet. [nowEpochMs] dates the last update.
 */
@Immutable
internal data class RvNodesState(val group: Group, val subscriptions: List<Subscription>, val protocols: Map<String, String>, val nowEpochMs: Long)

/**
 * A group's nodes in Revolut's list language (#06, #16): the group's name and kind with a round "test all" action, a
 * card per subscription with its traffic used against its quota (#13's goal), then one row per node with its flag, its
 * protocol and subscription, and its latency over a quality word. The selected node's flag carries a check.
 */
@Composable
internal fun RvNodesScreen(state: RvNodesState, onBack: () -> Unit, onTestAll: () -> Unit, onSelect: (String) -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val used = state.subscriptions.filter { sub -> state.group.members.any { it.subscriptionId == sub.id } }
    Column(Modifier.fillMaxSize().background(Rv.colors.page).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Top(state.group, onBack, onTestAll)
        RvGutter {
            used.forEach { SubscriptionCard(it, state.nowEpochMs) }
            NodeCard(state, onSelect)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Composable
private fun Top(group: Group, onBack: () -> Unit, onTestAll: () -> Unit) {
    val back = stringResource(R.string.ui_back)
    // The back glyph's edge sits on the gutter; its touch target reaches past it.
    Box(Modifier.height(RvSize.topBar).padding(start = RvSpace.gutter - (RvSize.touch - RvSize.navGlyph) / 2), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.size(RvSize.touch).rvPress(CircleShape, Role.Button, onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(R.drawable.rv_ic_back, RvSize.navGlyph, Rv.colors.text) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
            RvText(group.name, Rv.type.title)
            val count = group.members.size
            RvText("${stringResource(group.kind.label)} · ${pluralStringResource(R.plurals.policy_members, count, count)}", Rv.type.rowSub, color = Rv.colors.textSecondary)
        }
        Spacer(Modifier.width(RvSpace.trailingGap))
        RvAction(R.drawable.rv_ic_speed, stringResource(R.string.rv_test_all), onTestAll)
    }
    Spacer(Modifier.height(RvSpace.titleToCard))
}

/** #13's goal card: the name and the traffic used of the quota, the bar, then the expiry and the last update. */
@Composable
private fun SubscriptionCard(sub: Subscription, nowEpochMs: Long) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val used = sub.usedBytes
    val total = sub.totalBytes?.takeIf { it > 0 }
    RvCard {
        Column(Modifier.padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.barGap)) {
            Row {
                RvText(sub.name, Rv.type.rowTitle, Modifier.weight(1f))
                if (used != null && total != null) {
                    Spacer(Modifier.width(RvSpace.trailingGap))
                    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
                    RvText("${size(used)} / ${size(total)}", Rv.type.rowValue)
                }
            }
            if (used != null && total != null) RvProgress(used.toFloat() / total)
            Row {
                val expires = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, date(it, locale)) }
                RvText(expires.orEmpty(), Rv.type.rowSub, Modifier.weight(1f), color = Rv.colors.textSecondary)
                sub.updatedAtEpochMs?.let {
                    RvText(stringResource(R.string.rv_updated, updated(it, nowEpochMs, locale)), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun NodeCard(state: RvNodesState, onSelect: (String) -> Unit) {
    val subscriptions = state.subscriptions.associate { it.id to it.name }
    RvCard {
        for (node in state.group.members) {
            val subtitle = listOfNotNull(state.protocols[node.id], node.subscriptionId?.let(subscriptions::get)).joinToString(SEPARATOR)
            RvRow(
                { RvFlagDisc(placeOf(node.name), selected = node.id == state.group.selectedId) },
                withoutFlag(node.name),
                subtitle.ifEmpty { null },
                onClick = { onSelect(node.id) },
            ) { RvLatency(node.latencyMs?.takeUnless { node.failed }) }
        }
    }
}

private fun local(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())

private fun date(epochMs: Long, locale: Locale) = DATE.withLocale(locale).format(local(epochMs))

/**
 * The time of an update made today, else its date. Absolute, not "2 hours ago": the app's Noto subset has no 小 or 鐘,
 * and the sample may not grow it.
 */
private fun updated(epochMs: Long, nowEpochMs: Long, locale: Locale): String {
    val at = local(epochMs)
    return if (at.toLocalDate() == local(nowEpochMs).toLocalDate()) TIME.format(at) else date(epochMs, locale)
}

private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private const val SEPARATOR = " · "

private val GroupKind.label: Int
    get() = when (this) {
        GroupKind.Selector -> R.string.policy_kind_selector
        GroupKind.UrlTest -> R.string.policy_kind_urltest
        GroupKind.Fallback -> R.string.policy_kind_fallback
    }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodesScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodesScreen(sampleNodesState(), onBack = {}, onTestAll = {}, onSelect = {}) }
}
