package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.doona.android.designsystem.theme.DoonaDotMap
import dev.doona.android.designsystem.theme.DoonaTheme

private val SPARK = listOf(3f, 4f, 3.5f, 5f, 6f, 5.5f, 4.5f, 5f, 6.5f, 6f)
private val TRAFFIC = listOf(120f, 180f, 160f, 240f, 420f, 380f, 300f, 520f, 610f, 480f, 350f, 400f)
private val HISTORY = listOf(48, 45, 52, null, 61, 44, 42, 39, 47, 88, 51, 42)

/** Every two hours from 00:00 UTC, one per sample of [HISTORY]. */
private val TIMES = HISTORY.indices.map { "${(it * 2).toString().padStart(2, '0')}:00" }
private val SPREAD = listOf(38, 41, 42, 45, 47, 52, 60, 88, 120)

/** The hours of the day so far, to 14:30 UTC: one value each, the usage chart draws no hour after now. */
private val DOWN = List(15) { h -> (h * 37 % 11 + 2) * 1_000_000L }
private val UP = List(15) { h -> (h * 23 % 7 + 1) * 300_000L }
private val HONG_KONG = DoonaMapPlace("HK", 22.3f, 114.2f)
private val PLACES = setOf(HONG_KONG, DoonaMapPlace("JP", 35.7f, 139.7f), DoonaMapPlace("SG", 1.35f, 103.8f), DoonaMapPlace("US", 37.4f, -122.1f))

/**
 * Charts: the sparkline and the chart with no data, the traffic chart idle and scrubbed, the latency history and spread
 * with the median pill, the donut, the usage chart with its legend, the progress bar, and the hero figure.
 */
@Composable
internal fun RvChartsGallery() = GalleryPage("Charts") {
    val colors = DoonaTheme.colors
    val space = DoonaTheme.spacing
    Section("DoonaSparkline, DoonaGhostBars, DoonaEmptyChart") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            DoonaSparkline(SPARK, colors.down.line)
            DoonaGhostBars()
            DoonaEmptyChart("尚無流量", Modifier.height(DoonaTheme.chart.spark))
        }
    }
    Section("DoonaTrafficChart: idle, scrubbed") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            DoonaTrafficChart(TRAFFIC, colors.down, { "${it.toInt()} KB/s" }, scrub = null, onScrub = {})
            DoonaTrafficChart(TRAFFIC, colors.up, { "${it.toInt()} KB/s" }, scrub = 7, onScrub = {})
        }
    }
    Section("DoonaLatencyHistory, DoonaLatencySpread with the median pill") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            DoonaLatencyHistory(HISTORY, median = 47, TIMES, selected = HISTORY.lastIndex, onSelect = {}, label = { "$it ms" })
            DoonaLatencySpread(SPREAD, selected = 2, name = "香港 01", median = 47, label = { "$it ms" })
        }
    }
    Section("DoonaDonut, DoonaUsageChart and DoonaUsageLegend, DoonaProgressBar") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(space.cardGap), verticalAlignment = Alignment.CenterVertically) {
                DoonaDonut(listOf(DoonaDonutSegment(colors.categories[0], colors.track, used = 42, total = 100)))
                DoonaDonut(
                    listOf(
                        DoonaDonutSegment(colors.categories[0], colors.track, used = 30, total = 50),
                        DoonaDonutSegment(colors.categories[1], colors.track, used = 50, total = 50),
                    ),
                )
            }
            DoonaUsageChart(DOWN, UP, nowHour = 14.5f, Modifier.fillMaxWidth().height(DoonaGalleryDefaults.usage))
            DoonaUsageLegend("下載 1.2 GB", "上傳 310 MB")
            DoonaProgressBar(0.42f)
            DoonaProgressBar(1f)
        }
    }
    Section("DoonaHero, DoonaFigureText") {
        DoonaHero("1,234", ".56 MB")
        DoonaFigureText("42", " ms", DoonaTheme.type.cardValue, DoonaTheme.type.cardUnit)
    }
}

/** Maps: the dot map connected (its focus marked) and idle, and the world map with the places of a subscription. */
@Composable
internal fun RvMapsGallery() = GalleryPage("Maps") {
    val colors = DoonaTheme.colors
    val frame = Modifier.fillMaxWidth().height(DoonaGalleryDefaults.map).clip(DoonaTheme.shape.card).background(colors.page)
    Section("dotMap: connected to Hong Kong, idle") {
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.cardGap)) {
            Box(frame.dotMap(HONG_KONG, colors.dots, DoonaDotMap.focusTop))
            Box(frame.dotMap(null, colors.dotsIdle, DoonaDotMap.focusTop))
        }
    }
    Section("DoonaWorldMap") {
        DoonaCard { DoonaWorldMap(PLACES, Modifier.padding(DoonaTheme.spacing.rowPadding)) }
    }
}
