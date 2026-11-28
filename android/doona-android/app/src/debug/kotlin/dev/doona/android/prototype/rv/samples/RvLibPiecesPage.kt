package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_ACCENTS
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBarChart
import dev.doona.android.designsystem.rv.RvChartGrid
import dev.doona.android.designsystem.rv.RvColorChoice
import dev.doona.android.designsystem.rv.RvEmptyChartLine
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvMeter
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvPictureChoice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRing
import dev.doona.android.designsystem.rv.RvRingSegment
import dev.doona.android.designsystem.rv.RvRoundButton
import dev.doona.android.designsystem.rv.RvSchemes
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsAlpha
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsSection
import dev.doona.android.designsystem.rv.RvSettingsSize
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatsAlpha
import dev.doona.android.designsystem.rv.RvStatsSize
import dev.doona.android.designsystem.rv.RvSwatch
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.designsystem.rv.primary
import dev.doona.android.designsystem.rv.rvPictureFrame
import dev.doona.android.feature.model.RvThemeMode

/**
 * The library pieces lifted from the pages (owner, 2026-11-27: 「可以加強增加我們的組件庫但是請不要自己亂畫。必須統一風格」):
 * each drawn as the page it came from draws it, with that page's values. The pages switch to them after the merge.
 */
internal enum class RvLibPiece(val title: String) {
    Meter("RvMeter"),
    Ring("RvRing"),
    Bars("RvBarChart"),
    RoundButton("RvRoundButton"),
    Picture("RvPictureChoice"),
    Color("RvColorChoice"),
    Frame("rvPictureFrame"),
}

/** One library piece's sample: its name as the title, then a titled card per state it has. */
@Composable
internal fun RvLibPiecesPage(piece: RvLibPiece, schemes: RvSchemes) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(piece.title, insets, onBack = {}, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(piece.title, titleBlock, insets)
        RvSettingsColumn {
            when (piece) {
                RvLibPiece.Meter -> Meter()
                RvLibPiece.Ring -> Ring()
                RvLibPiece.Bars -> Bars()
                RvLibPiece.RoundButton -> RoundButton()
                RvLibPiece.Picture -> Picture(schemes)
                RvLibPiece.Color -> ColorChoice(schemes)
                RvLibPiece.Frame -> Frame(schemes)
            }
        }
    }
}

/** 分流比例's two meters with figures, then the same two with none: bare tracks and the labels alone. */
@Composable
private fun Meter() {
    for (known in listOf(true, false)) {
        RvSettingsSection(sampleText(if (known) "有數值" else "無數值", if (known) "有数值" else "无数值", if (known) "Known" else "Unknown"), first = known)
        RvSettingsCard {
            Column(Modifier.padding(RvSpace.rowPadding)) {
                RvMeter(PROXY, "62%", stringResource(R.string.rv_stats_proxy), Rv.colors.down.line, known)
                RvMeter(1f - PROXY, "38%", stringResource(R.string.rv_stats_direct), Rv.colors.textSecondary, known)
            }
        }
    }
}

/** 訂閱流量's ring with two subscriptions (one partly used, one used up), and with none: the bare track. */
@Composable
private fun Ring() {
    val palette = Rv.colors.categories
    val card = Rv.colors.card
    val segments = QUOTAS.mapIndexed { i, (used, total) ->
        val color = palette[i % palette.size]
        RvRingSegment(color, lerp(color, card, RvStatsAlpha.RING_REST), used, total)
    }
    RvSettingsSection(sampleText("兩份訂閱", "两份订阅", "Two subscriptions"), first = true)
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), horizontalArrangement = Arrangement.SpaceEvenly) {
            RvRing(segments)
            RvRing(segments.take(1))
        }
    }
    RvSettingsSection(sampleText("無配額", "无配额", "No quota"))
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), horizontalArrangement = Arrangement.Center) { RvRing(emptyList()) }
    }
}

/** A paged tile's bars: today so far (the rest are stubs), a week, and the empty grid with its line. */
@Composable
private fun Bars() {
    RvSettingsSection(sampleText("今天", "今天", "Today"), first = true)
    RvSettingsCard { RvBarChart(TODAY, Rv.colors.down.line, Modifier.padding(RvSpace.rowPadding)) }
    RvSettingsSection(sampleText("本週", "本周", "This week"))
    RvSettingsCard { RvBarChart(WEEK, Rv.colors.up.line, Modifier.padding(RvSpace.rowPadding), slots = WEEK.size) }
    RvSettingsSection(sampleText("無資料", "无数据", "No data"))
    RvSettingsCard {
        Box(Modifier.fillMaxWidth().padding(RvSpace.rowPadding).height(RvStatsSize.bars), contentAlignment = Alignment.Center) {
            RvChartGrid(RvUsageSize.HOURS, RvUsageSize.GRID_HOURS, Modifier.matchParentSize())
            RvEmptyChartLine(stringResource(R.string.rv_stats_no_data))
        }
    }
}

/** The 節點 page's floating actions: the primary button and the round companion on one centre line. */
@Composable
private fun RoundButton() {
    RvSettingsSection(sampleText("浮動按鈕", "浮动按钮", "Floating actions"), first = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvNodesSize.ctaGap), verticalAlignment = Alignment.CenterVertically) {
        RvPrimaryButton(stringResource(R.string.rv_nodes_use), {}, Modifier.weight(1f))
        RvRoundButton(R.drawable.rv_sym_speed, stringResource(R.string.rv_test_latency), {})
    }
}

/** 主題's 模式 card: the three modes as pictures, 跟隨系統 picked. */
@Composable
private fun Picture(schemes: RvSchemes) {
    val light = remember(schemes) { schemes.colors(dark = false) }
    val dark = remember(schemes) { schemes.colors(dark = true) }
    RvSettingsSection(stringResource(R.string.rv_settings_mode), first = true)
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.tight)) {
            RvThemeMode.entries.forEach { mode ->
                RvPictureChoice(stringResource(mode.label), mode == RvThemeMode.System, {}, Modifier.weight(1f)) {
                    when (mode) {
                        RvThemeMode.Light -> RvSwatch(light)
                        RvThemeMode.Dark -> RvSwatch(dark)
                        RvThemeMode.System -> RvSwatch(light, split = dark)
                    }
                }
            }
        }
    }
}

/** 強調色's discs, the scheme's own first and picked; then the same row dimmed, as while the wallpaper is followed. */
@Composable
private fun ColorChoice(schemes: RvSchemes) {
    val own = schemes.primary(Rv.colors.dark)
    val colours = listOf(R.string.rv_settings_accent_current to own) + RV_ACCENTS.map { it.name to it.primary(Rv.colors.dark) }
    for (enabled in listOf(true, false)) {
        RvSettingsSection(sampleText(if (enabled) "可選" else "停用", if (enabled) "可选" else "停用", if (enabled) "Enabled" else "Disabled"), first = enabled)
        RvSettingsCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RvSettingsSpace.accentInset, vertical = RvSettingsSpace.accentPad)
                    .graphicsLayer { alpha = if (enabled) 1f else RvSettingsAlpha.DISABLED },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                colours.forEachIndexed { i, (name, colour) -> RvColorChoice(colour, stringResource(name), i == 0, enabled, {}) }
            }
        }
    }
}

/** The frame a picture in a card wears: round a mode picture, and round a bare box of the page's and the card's colour. */
@Composable
private fun Frame(schemes: RvSchemes) {
    val light = remember(schemes) { schemes.colors(dark = false) }
    RvSettingsSection(sampleText("圖片外框", "图片外框", "Picture frame"), first = true)
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), horizontalArrangement = Arrangement.SpaceEvenly) {
            RvSwatch(light)
            Box(Modifier.size(RvSettingsSize.modeThumb).rvPictureFrame().background(Rv.colors.page))
            Box(Modifier.size(RvSettingsSize.modeThumb).rvPictureFrame().background(Rv.colors.card))
        }
    }
}

private const val PROXY = 0.62f
private const val GB = 1_000_000_000L
private val QUOTAS = listOf(62 * GB to 200 * GB, 50 * GB to 50 * GB)
private val TODAY = listOf(2L, 0L, 0L, 1L, 0L, 0L, 3L, 9L, 14L, 11L, 6L, 8L, 12L, 15L, 7L)
private val WEEK = listOf(40L, 55L, 32L, 70L, 64L, 21L, 48L)
