package dev.doona.android.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/*
 * The design system on five pages (the three here, and `InputsGallery` and `FeedbackGallery`), every component in every
 * state it has. The preview pane and the screenshot goldens show the same pages, so a component that is added or changed
 * shows up in both. Sample names and numbers are made up.
 */

private val SERIES = listOf(3f, 4f, 3.5f, 5f, 6f, 5.5f, 4.5f, 5f, 6.5f, 6f)

@Composable
internal fun GalleryPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val space = DoonaTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(DoonaTheme.colors.surface)
            .windowInsetsPadding(DoonaTheme.insets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.sheetTop),
        verticalArrangement = Arrangement.spacedBy(space.section),
    ) {
        DoonaPageTitle(title)
        content()
    }
}

@Composable
internal fun Section(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
        DoonaText(name, DoonaTheme.type.caption, color = DoonaTheme.colors.text.secondary)
        content()
    }
}

/** Controls: segmented, chips, pickers, the switch row, icon actions and page tabs. */
@Composable
internal fun ControlsGallery() = GalleryPage("Controls") {
    val space = DoonaTheme.spacing
    val tabs = listOf(stringResource(R.string.nav_policies), stringResource(R.string.nav_nodes), stringResource(R.string.nav_rules))
    Section("DoonaSegmented") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaSegmented(listOf(stringResource(R.string.policy_both), stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp)), selected = 0, onSelect = {})
            DoonaSegmented(listOf(stringResource(R.string.mode_global), stringResource(R.string.mode_direct)), selected = 1, onSelect = {})
        }
    }
    Section("DoonaTagChip") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.item)) {
            DoonaTagChip("Auto")
            DoonaTagChip("HK")
        }
    }
    Section("DoonaPicker, plain and filled") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaPicker(stringResource(R.string.policy_all_regions), onClick = {})
            DoonaFilterPicker(stringResource(R.string.policy_by_latency), onClick = {})
        }
    }
    Section("DoonaSwitchRow, on and off") {
        listOf(true, false).forEach { on ->
            DoonaSwitchRow(on, onChange = {}) {
                DoonaText(stringResource(R.string.policy_alive_only), DoonaTheme.type.body, maxLines = 1)
            }
        }
    }
    Section("DoonaIconAction") {
        Row {
            DoonaIconAction(R.drawable.ic_search, stringResource(R.string.ui_search), onClick = {})
            DoonaIconAction(R.drawable.ic_edit, stringResource(R.string.policy_edit), onClick = {})
            DoonaIconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options), onClick = {})
        }
    }
    Section("DoonaPageTabs") { DoonaPageTabs(tabs, selected = 1) }
}

/** Cards and tiles: the card, control cards, metric tiles, node tiles and the error banner. */
@Composable
internal fun CardsGallery() = GalleryPage("Cards") {
    val space = DoonaTheme.spacing
    Section("DoonaCard, plain and tappable") {
        DoonaCard(Modifier.fillMaxWidth()) { DoonaText("hk-01", DoonaTheme.type.cardTitle, maxLines = 1) }
        DoonaCard(Modifier.fillMaxWidth(), onClick = {}) {
            DoonaText("Proxy", DoonaTheme.type.cardTitle, maxLines = 1)
            DoonaText(stringResource(R.string.group_every_node), DoonaTheme.type.caption, color = DoonaTheme.colors.text.secondary)
        }
    }
    Section("DoonaControlCard") {
        DoonaControlCard(R.drawable.ic_shuffle, stringResource(R.string.act_mode)) {
            DoonaSegmented(listOf(stringResource(R.string.mode_global), stringResource(R.string.mode_direct)), selected = 0, onSelect = {})
        }
        DoonaControlCard(R.drawable.ic_filter, stringResource(R.string.act_global)) {
            DoonaPicker("Auto", onClick = {})
        }
    }
    Section("DoonaMetricTile") {
        DoonaTileRow {
            MetricTile(R.drawable.ic_download, DoonaTheme.colors.series1, stringResource(R.string.ui_download), "3.9 MB/s")
            MetricTile(R.drawable.ic_upload, DoonaTheme.colors.series2, stringResource(R.string.ui_upload), "330 kB/s")
        }
        DoonaMetricTile(R.drawable.ic_clock, { DoonaNodeName("hk-01", DoonaTheme.type.caption, color = DoonaTheme.colors.text.secondary) }, Modifier.fillMaxWidth()) {
            DoonaMetricLatency(42)
        }
    }
    Section("DoonaNodeTile: plain, current, group, untested") {
        Column(verticalArrangement = Arrangement.spacedBy(space.item)) {
            DoonaNodeTile("hk-01", 42, current = false, groupLabel = null, onClick = {})
            DoonaNodeTile("jp-01", 171, current = true, groupLabel = null, onClick = {}, source = "Tokyo 1")
            DoonaNodeTile("Auto", 88, current = false, groupLabel = stringResource(R.string.ui_group), onClick = {})
            DoonaNodeTile("us-01", null, current = false, groupLabel = null, onClick = {})
        }
    }
    Section("DoonaErrorBanner") { DoonaErrorBanner(stringResource(R.string.error_busy), stringResource(R.string.ui_dismiss), onDismiss = {}) }
}

@Composable
private fun RowScope.MetricTile(icon: Int, seriesColor: Color, caption: String, value: String) {
    DoonaMetricTile(icon, { DoonaTileCaption(caption) }, Modifier.weight(1f)) {
        DoonaMetricValue(value)
        DoonaSparkline(SERIES, seriesColor, Modifier.fillMaxWidth())
    }
}

/** Text, status and icons: the type ramp, status lights, latency in each tone, the badge, chevrons, icons and flags. */
@Composable
internal fun StatusGallery() = GalleryPage("Status and text") {
    val space = DoonaTheme.spacing
    Section("Type") { typeRamp().forEach { (name, style) -> DoonaText(name, style, maxLines = 1) } }
    // The items wrap onto a new line when a larger font leaves no room for them side by side: none is cut at the edge.
    Section("DoonaStatusLight: ok, warn, err, neutral, small") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.block), verticalArrangement = Arrangement.spacedBy(space.item)) {
            DoonaStatusLight(Tone.Ok, stringResource(R.string.backend_connected))
            DoonaStatusLight(Tone.Warn, stringResource(R.string.status_starting))
            DoonaStatusLight(Tone.Err, stringResource(R.string.status_stopped))
            DoonaStatusLight(Tone.Neutral, stringResource(R.string.policy_current))
            DoonaSmallStatusLight(Tone.Ok, stringResource(R.string.act_latency))
        }
    }
    Section("DoonaLatency: under 100, under 300, over, untested") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.section), verticalArrangement = Arrangement.spacedBy(space.item)) {
            listOf(42, 171, 480, null).forEach { DoonaLatency(it) }
        }
    }
    Section("DoonaBadge") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.item)) {
            DoonaBadge(stringResource(R.string.ui_group))
            DoonaBadge(stringResource(R.string.policy_kind_manual, 3))
        }
    }
    Section("DoonaChevron, DoonaIcon, DoonaFlag") { glyphs() }
    Section("Chart series: download, upload") { swatches() }
}

@Composable
private fun typeRamp(): List<Pair<String, TextStyle>> = with(DoonaTheme.type) {
    listOf("pageTitle 活動 Proxy" to pageTitle, "cardTitle 已連線" to cardTitle, "label 目前 Auto" to label, "body 包含所有節點" to body, "bodyStrong hk-01" to bodyStrong, "caption 3.9 MB/s" to caption, "tileValue 42 毫秒" to tileValue)
}

@Composable
private fun glyphs() {
    val c = DoonaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block), verticalAlignment = Alignment.CenterVertically) {
        DoonaChevron(ChevronDirection.Down, c.text.secondary)
        DoonaChevron(ChevronDirection.Right, c.text.secondary)
        DoonaIcon(R.drawable.ic_speed_fast, c.accent)
        DoonaIcon(R.drawable.ic_link, c.icon)
        DoonaFlag(R.drawable.flag_hk, "HK")
        DoonaFlag(R.drawable.flag_jp, "JP")
        DoonaFlag(R.drawable.flag_us, "US")
    }
}

@Composable
private fun swatches() {
    val c = DoonaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
        listOf(c.series1, c.series2).forEach {
            Box(Modifier.size(DoonaTheme.size.control).clip(DoonaTheme.shape.control).background(it))
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ControlsGalleryPreview() = DoonaTheme { ControlsGallery() }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardsGalleryPreview() = DoonaTheme { CardsGallery() }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusGalleryPreview() = DoonaTheme { StatusGallery() }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InputsGalleryPreview() = DoonaTheme { InputsGallery() }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FeedbackGalleryPreview() = DoonaTheme { FeedbackGallery() }
