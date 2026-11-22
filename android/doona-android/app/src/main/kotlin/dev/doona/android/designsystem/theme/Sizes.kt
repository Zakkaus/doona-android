package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.ChartScale
import dev.doona.android.designsystem.tokens.SizeScale

/** Component metrics (§5, §6, §7, §8). */
@Immutable
object DoonaSize {
    /** Control height on touch (§7). */
    val control = SizeScale.control

    /** Android touch target and row minimum; doona's own touch control is 36. */
    val touch = SizeScale.touch
    val topBar = SizeScale.topBar
    val bottomBar = SizeScale.bottomBar
    val railWidth = SizeScale.railWidth
    val logo = SizeScale.logo

    /** Icons: 25 on touch, 20 on the grid, 16 inside a button (§6). */
    val icon = SizeScale.icon
    val iconSmall = SizeScale.iconSmall
    val iconInline = SizeScale.iconInline
    val hubPillWidth = SizeScale.hubPillWidth
    val hubPillHeight = SizeScale.hubPillHeight
    val border = SizeScale.border
    val borderStrong = SizeScale.borderStrong
    val dot = SizeScale.dot
    val dotSmall = SizeScale.dotSmall
    val badgeHeight = SizeScale.badgeHeight
    val nodeTileMin = SizeScale.nodeTileMin
    val tabUnderline = SizeScale.tabUnderline
    val spark = SizeScale.spark
    val sparkStroke = SizeScale.sparkStroke
    val flag = SizeScale.flag
    val flagLetters = SizeScale.flagLetters
    val flagEdge = SizeScale.flagEdge
    val chartInset = SizeScale.chartInset
    val appIcon = SizeScale.appIcon
    val checkbox = SizeScale.checkbox
    val meterTrack = SizeScale.meterTrack
    val spinnerStroke = SizeScale.spinnerStroke
    val fieldFade = SizeScale.fieldFade
    val emptyIcon = SizeScale.emptyIcon
    val dialogMax = SizeScale.dialogMax
    val contentMax = SizeScale.contentMax
    val compactHeight = SizeScale.compactHeight
}

/** Chart style (§7, Charts). */
@Immutable
object DoonaChart {
    const val FILL_TOP = ChartScale.FILL_TOP
    const val FILL_BOTTOM = ChartScale.FILL_BOTTOM
    const val FILL_OPACITY = ChartScale.FILL_OPACITY
    const val DOMAIN_LOW = ChartScale.DOMAIN_LOW
    const val DOMAIN_HIGH = ChartScale.DOMAIN_HIGH
}
