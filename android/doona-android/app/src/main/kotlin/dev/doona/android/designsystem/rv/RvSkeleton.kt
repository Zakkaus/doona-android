package dev.doona.android.designsystem.rv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The skeleton: grey bars, discs and blocks in the places of the parts they stand for, while a page waits for its data.

/**
 * The skeleton's own metrics. Revolut shows content that needs data first as grey rounded bars, discs and blocks shaped
 * like it, under a light shimmer, and fills it in within 0.5 to 1 s (revolut-UX §11 and rows 42, 61, 90; sheet-08, -13,
 * -21). Those were seen in motion, not measured in px, so the shares below are by eye; every length that places a part
 * is the real part's token, so the content lands where its skeleton stood.
 */
internal object RvSkeletonSize {
    /** A bar is this share of its text's size tall, so the bars of a title and its subtitle keep a gap, as their ink does. */
    const val BAR = 0.7f

    /** Row n's title and subtitle bars as shares of the text's width, taken in turn, so the rows do not read as a grid. */
    val titles = listOf(0.62f, 0.46f, 0.7f, 0.54f)
    val subtitles = listOf(0.4f, 0.58f, 0.32f, 0.48f)

    /** A row's end (a latency, a day's bytes) at the row value's size: `120 ms` is about as wide. */
    val end = 56.dp

    /** The smaller line under a row's end value (a latency's change, `▼ 3 ms`). */
    val endSub = 44.dp

    /** A card header's, a see-all line's and a control's caption bar, as shares of their line. */
    const val HEADER = 0.3f
    const val SEE_ALL = 0.4f
    const val CAPTION = 0.72f

    /** A goal's used amount at the start and its end date at the end of the line over its bar. */
    const val GOAL_USED = 0.42f
    const val GOAL_ENDS = 0.26f

    /** A tile's caption, its figure and the note under it. */
    const val TILE_CAPTION = 0.5f
    const val TILE_FIGURE = 0.62f
    const val TILE_NOTE = 0.42f

    /** The hero's state, its figure and the line under it, centred, as shares of the page's width. */
    const val HERO_CAPTION = 0.36f
    const val HERO_FIGURE = 0.44f
    const val HERO_LINE = 0.22f

    /** The shimmer's band, as a share of the page's width; it crosses the page once each [RvMotion.BUSY_MS]. */
    const val BAND = 0.5f
}

/**
 * What a skeleton line stands for. At a large font size a line that mixes Han and Latin (`未連線 · 規則`) is set a little
 * taller than one in either script alone, so the line takes the height of its own kind of text.
 */
internal enum class RvSkeletonText(val sample: String) {
    /** Words in one script: a name, a group, a caption. */
    Words("節"),

    /** A figure and its unit: a rate, an amount. */
    Figures("0"),

    /** Han and Latin on one line: a state and its mode, an amount and its date. */
    Mixed("節 · 0"),
}

/**
 * What a skeleton row has at its end, each on the lines its row's end takes: the [first] line's kind of text, at the row
 * value's size, and the [second]'s under it, at the subtitle's, on the title's and the subtitle's lines.
 */
internal enum class RvSkeletonEnd(val first: RvSkeletonText?, val second: RvSkeletonText?) {
    None(null, null),

    /** One value centred on the row: a rule's bytes. */
    Value(RvSkeletonText.Figures, null),

    /** A latency over its change since the test before (`32 毫秒` over `▼ 3 毫秒`). */
    Latency(RvSkeletonText.Mixed, RvSkeletonText.Mixed),

    /** A group's node over its latency (`Taipei 04` over `26 毫秒`). */
    Node(RvSkeletonText.Figures, RvSkeletonText.Mixed),
}

/**
 * Where a page's content stops being in the clear, in root px: the top of the room a floating bottom action keeps
 * ([RvScrollPage]'s, the gap above the action and the action); null on a page with none. A skeleton row that would end
 * past it is not drawn, so no skeleton stands under the action, as the loaded list's last row clears it.
 */
internal val LocalRvContentFloor = staticCompositionLocalOf<(() -> Float)?> { null }

/**
 * A still of the shimmer at this point of its pass, from 0 (the band before the page's start) to 1 (past its end), for
 * the goldens, which cannot show motion; null (the default) runs the pass.
 */
internal val LocalRvShimmerAt = staticCompositionLocalOf<Float?> { null }

/**
 * Shows [skeleton] while [loading], then [content] in its place: the content fades in as the skeleton fades out, over
 * [RvMotion.HERO_FADE_MS] (Revolut's grey placeholder turning into the hero art, revolut-UX §11), both at [alignment] in
 * the room the larger takes, so nothing under them moves while they cross; the skeleton's parts share one shimmer pass
 * ([RvShimmerPass]). At animator scale 0 at once. A place that has not waited yet draws its content alone, with no
 * fade's layer round it, so a page that never loads draws as before.
 */
@Composable
internal fun RvSkeletonFade(
    loading: Boolean,
    skeleton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit,
) {
    val motion = Rv.motion
    var waited by remember { mutableStateOf(loading) }
    if (loading && !waited) SideEffect { waited = true }
    if (!waited) {
        Box(modifier, contentAlignment = alignment) { if (loading) RvShimmerPass(skeleton) else content() }
        return
    }
    AnimatedContent(
        loading,
        modifier,
        transitionSpec = {
            val fade = motion.tween<Float>(RvMotion.HERO_FADE_MS, LinearEasing)
            ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = null)
        },
        contentAlignment = alignment,
        label = "skeleton",
    ) { if (it) RvShimmerPass(skeleton) else content() }
}

/** [RvSkeletonFade] for a run of cards, [RvSpace.cardGap] apart, as in a [RvGutter]. */
@Composable
internal fun RvSkeletonCards(loading: Boolean, skeleton: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RvSkeletonFade(loading, { CardRun(skeleton) }) { CardRun(content) }
}

@Composable
private fun CardRun(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap), content = content)
}

/**
 * A card of skeleton parts: [RvCard] itself, its parts under one shimmer ([rvShimmer]). [description] tells a screen
 * reader what is coming; without it the skeleton is silent, as its bars say nothing.
 */
@Composable
internal fun RvSkeletonCard(modifier: Modifier = Modifier, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val named = description?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    RvCard(modifier.then(named)) { Column(Modifier.rvShimmer(), content = content) }
}

/**
 * [RvRow]'s skeleton, row [index] of its card: the disc, the title's bar over the subtitle's of [subtitle]'s kind (or the
 * title's alone), then the [end]'s bars and the room of the row's chevron when it has one ([chevron]), in the row's own
 * heights, insets and gaps. Each bar sits on its text's line ([RvSkeletonLine]), so at any font size the row is as tall
 * as a row whose text keeps one line. A row whose words the page knows before its data ([title], [subtitleText]: 出站模式
 * over the mode) takes their lines ([RvSkeletonLabel]). Beside an end of two lines the title's bar keeps to the room a title
 * keeps beside its end ([besideEnd]), so it does not reach under a long end (a group's `Hong Kong 02 IPLC`) as the
 * content fades in. Past [LocalRvContentFloor] the row keeps its place undrawn.
 */
@Composable
internal fun RvSkeletonRow(
    index: Int,
    subtitle: RvSkeletonText? = RvSkeletonText.Words,
    end: RvSkeletonEnd = RvSkeletonEnd.None,
    chevron: Boolean = false,
    title: String? = null,
    subtitleText: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .rvAboveFloor()
            .heightIn(min = if (subtitle != null) RvSize.rowTwoLine else RvSize.rowOneLine)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvSkeletonDisc()
        Spacer(Modifier.width(RvSpace.rowGap))
        Column(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset)) {
            if (title != null) {
                RvSkeletonLabel(title, Rv.type.rowTitle, maxLines = RV_ROW_TITLE_LINES)
            } else {
                val room = if (end.second != null) Modifier.besideEnd(RvSkeletonSize.end) else Modifier
                RvSkeletonLine(Rv.type.rowTitle, RvSkeletonSize.titles[index % RvSkeletonSize.titles.size], room)
            }
            when {
                subtitle == null -> Unit
                subtitleText != null -> RvSkeletonLabel(subtitleText, Rv.type.rowSub, maxLines = RV_ROW_SUBTITLE_LINES)
                else -> RvSkeletonLine(Rv.type.rowSub, RvSkeletonSize.subtitles[index % RvSkeletonSize.subtitles.size], kind = subtitle)
            }
        }
        SkeletonEnd(end)
        if (chevron) {
            Spacer(Modifier.width(RvSpace.trailingGap))
            Spacer(Modifier.size(RvChevronSize.box))
        }
    }
}

/** A skeleton row's [end]: a value's bar centred on the row, or a value's over a smaller line's, at the row's end. */
@Composable
private fun SkeletonEnd(end: RvSkeletonEnd) {
    val first = end.first ?: return
    Spacer(Modifier.width(RvSpace.trailingGap))
    val second = end.second ?: return RvSkeletonLine(Rv.type.rowValue, 1f, Modifier.width(RvSkeletonSize.end), first)
    Column(horizontalAlignment = Alignment.End) {
        RvSkeletonLine(Rv.type.rowValue, 1f, Modifier.width(RvSkeletonSize.end), first)
        RvSkeletonLine(Rv.type.rowSub, 1f, Modifier.width(RvSkeletonSize.endSub), second)
    }
}

/**
 * The room a row's title keeps beside its line-wise end, as [RvRow] gives it: [RV_ROW_TITLE_SHARE] of the line (this
 * column, the gap and the end's [end] wide room) less the gap, or the column where that is narrower.
 */
private fun Modifier.besideEnd(end: Dp): Modifier = layout { measurable, constraints ->
    val gap = RvSpace.trailingGap.roundToPx()
    val room = ((constraints.maxWidth + gap + end.roundToPx()) * RV_ROW_TITLE_SHARE).toInt() - gap
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room.coerceIn(0, constraints.maxWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** [RvCardHeader]'s skeleton: its [title]'s bar (when the page knows it) on the header's line, in the header's height and inset. */
@Composable
internal fun RvSkeletonHeader(title: String? = null) {
    Box(
        Modifier.padding(top = RvSpace.headerInset).fillMaxWidth().height(RvHeaderSize.row).padding(horizontal = RvSpace.rowPadding),
        contentAlignment = Alignment.CenterStart,
    ) { if (title != null) RvSkeletonLabel(title, Rv.type.cardTitle, maxLines = 1) else RvSkeletonLine(Rv.type.cardTitle, RvSkeletonSize.HEADER) }
}

/** [RvSeeAll]'s skeleton: its bar centred in the see-all line's height, on a line of words and a count. */
@Composable
internal fun RvSkeletonSeeAll() {
    Box(Modifier.fillMaxWidth().height(RvSize.seeAll).padding(horizontal = RvSpace.rowPadding), contentAlignment = Alignment.Center) {
        RvSkeletonLine(Rv.type.seeAll, RvSkeletonSize.SEE_ALL, kind = RvSkeletonText.Mixed)
    }
}

/** [RvControlCard]'s skeleton with a segmented control: the control's track, then its [caption]'s lines (when the page knows it). */
@Composable
internal fun RvSkeletonControlCard(caption: String? = null) {
    RvSkeletonCard {
        Column(Modifier.padding(RvSpace.rowPadding)) {
            RvSkeletonBlock(rvSegmentHeight(Rv.type.segment), shape = CircleShape)
            Spacer(Modifier.height(RvSpace.controlToCaption))
            if (caption != null) RvSkeletonLabel(caption, Rv.type.rowSub) else RvSkeletonLine(Rv.type.rowSub, RvSkeletonSize.CAPTION)
        }
    }
}

/**
 * [RvRangeRow]'s skeleton, a chip per label of [labels] (the page knows its orders before its data), [RvChartSize.rangeGap]
 * apart under one shimmer: each a pill of the chip's height at this font size, as wide as its label and its pads.
 */
@Composable
internal fun RvSkeletonChips(labels: List<String>, modifier: Modifier = Modifier) {
    val style = Rv.type.action
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widths = remember(labels, style, measurer) { labels.map { measurer.measure(scripted(it), style, maxLines = 1).size.width } }
    val height = maxOf(RvChartSize.range, with(density) { style.lineHeight.toDp() } + RvChartSize.rangeInset * 2)
    Row(modifier.rvShimmer(), horizontalArrangement = Arrangement.spacedBy(RvChartSize.rangeGap)) {
        widths.forEach { RvSkeletonBlock(height, Modifier.width(with(density) { it.toDp() } + RvChartSize.rangePad * 2), CircleShape) }
    }
}

/**
 * A goal's skeleton, under its card's row (Revolut's goal card, #13, as 訂閱 shows a quota): the used amount's and the
 * end date's bars on one line, then the progress bar's track.
 */
@Composable
internal fun RvSkeletonGoal() {
    Column(Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)) {
        Box(Modifier.fillMaxWidth()) {
            RvSkeletonLine(Rv.type.rowSub, RvSkeletonSize.GOAL_USED, kind = RvSkeletonText.Mixed)
            RvSkeletonLine(Rv.type.rowSub, RvSkeletonSize.GOAL_ENDS, Modifier.align(Alignment.CenterEnd), RvSkeletonText.Mixed)
        }
        Spacer(Modifier.height(RvSpace.barGap))
        RvSkeletonBlock(RvSize.progress, shape = CircleShape)
    }
}

/**
 * Two tiles' skeletons side by side ([RvTileRow], [RvSparkTile]), square, as the tiles are at the default font size,
 * with their [captions] when the page knows them.
 */
@Composable
internal fun RvSkeletonTiles(captions: List<String?> = List(TILES) { null }) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvSpace.tileGap)) {
        captions.forEach { SkeletonTile(it, Modifier.weight(1f)) }
    }
}

/** [RvSparkTile]'s skeleton: the [caption]'s bar beside the series disc, the figure's and the note's bars, the chart's block. */
@Composable
private fun SkeletonTile(caption: String?, modifier: Modifier) {
    RvSkeletonCard(modifier.aspectRatio(1f)) {
        Column(Modifier.fillMaxSize().padding(RvChartSize.tilePad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (caption != null) RvSkeletonLabel(caption, Rv.type.cardTitle, maxLines = 1) else RvSkeletonLine(Rv.type.cardTitle, RvSkeletonSize.TILE_CAPTION)
                }
                RvSkeletonDisc(RvChartSize.tileDisc)
            }
            Spacer(Modifier.height(RvSpace.tight))
            RvSkeletonLine(Rv.type.tileValue, RvSkeletonSize.TILE_FIGURE, kind = RvSkeletonText.Figures)
            RvSkeletonLine(Rv.type.tileCaption, RvSkeletonSize.TILE_NOTE)
            Spacer(Modifier.weight(1f))
            RvSkeletonBlock(RvChartSize.spark)
        }
    }
}

/** The hero's skeleton: the state's bar over the figure's over the line's under it, centred, on the hero's lines and gaps. */
@Composable
internal fun RvSkeletonHero() {
    Column(Modifier.rvShimmer(), horizontalAlignment = Alignment.CenterHorizontally) {
        RvSkeletonLine(Rv.type.caption, RvSkeletonSize.HERO_CAPTION, kind = RvSkeletonText.Mixed)
        Spacer(Modifier.height(RvSpace.tight))
        RvSkeletonLine(Rv.type.hero, RvSkeletonSize.HERO_FIGURE, kind = RvSkeletonText.Figures)
        Spacer(Modifier.height(RvSpace.subGap))
        RvSkeletonLine(Rv.type.sub, RvSkeletonSize.HERO_LINE, kind = RvSkeletonText.Figures)
    }
}

/**
 * A line of [kind] in [style] as a bar: the line is as tall as [RvText] sets one line of that text at this font scale
 * ([rvOneLine]), [width] of the room wide, and the bar ([RvSkeletonSize.BAR] of the text's size) is centred on it.
 */
@Composable
internal fun RvSkeletonLine(style: TextStyle, width: Float, modifier: Modifier = Modifier, kind: RvSkeletonText = RvSkeletonText.Words) {
    Box(modifier.fillMaxWidth(width).height(rvOneLine(kind.sample, style)).testTag(RvPart.SKELETON_LINE), contentAlignment = Alignment.Center) { Bar(style, Modifier.fillMaxWidth()) }
}

/**
 * A line of words the page knows before its data (a card's header, a control's caption, 出站模式) as bars: set as [RvText]
 * sets [text] in [style], up to [maxLines], each line's bar ([RvSkeletonSize.BAR] of the text's size) as wide as that
 * line's words and centred on it, so the skeleton takes the text's own box at any font size. Silent to a screen reader.
 */
@Composable
internal fun RvSkeletonLabel(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    var lines by remember { mutableStateOf<TextLayoutResult?>(null) }
    val track = Rv.colors.track
    val tall = with(LocalDensity.current) { style.fontSize.toPx() } * RvSkeletonSize.BAR
    BasicText(
        scripted(text),
        modifier.testTag(RvPart.SKELETON_LINE).clearAndSetSemantics {}.drawBehind {
            val laid = lines ?: return@drawBehind
            for (line in 0 until laid.lineCount) {
                val left = laid.getLineLeft(line)
                val middle = (laid.getLineTop(line) + laid.getLineBottom(line)) / 2
                drawRoundRect(track, Offset(left, middle - tall / 2), Size(laid.getLineRight(line) - left, tall), CornerRadius(tall / 2))
            }
        },
        style.copy(color = Color.Transparent),
        onTextLayout = { lines = it },
        maxLines = maxLines,
    )
}

/**
 * How many skeleton rows of at least [row] run a list's skeleton past the window's foot from anywhere on it, so a list
 * longer than the window keeps its card's edges in view as its skeleton had them.
 */
@Composable
internal fun rvSkeletonRowsToFill(row: Dp = RvSize.rowTwoLine): Int {
    val window = LocalWindowInfo.current.containerSize.height
    val pitch = with(LocalDensity.current) { row.roundToPx() }
    return window / pitch.coerceAtLeast(1) + 1
}

/** Draws this skeleton part only while it ends above [LocalRvContentFloor]; laid out as ever, so nothing under it moves. */
@Composable
private fun Modifier.rvAboveFloor(): Modifier {
    val floor = LocalRvContentFloor.current ?: return this
    val bottom = remember { mutableFloatStateOf(0f) }
    return onGloballyPositioned { bottom.floatValue = it.positionInRoot().y + it.size.height }
        .drawWithContent { if (bottom.floatValue <= floor()) drawContent() }
}

/** A chart's or a bar's place: a block [height] tall across the room, in [shape]. */
@Composable
internal fun RvSkeletonBlock(height: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Rv.highlightRadius)) {
    Box(modifier.fillMaxWidth().height(height).testTag(RvPart.SKELETON_BLOCK).clip(shape).background(Rv.colors.track))
}

/** A row's disc as it stands before its glyph, flag or app icon: the disc's fill alone. */
@Composable
internal fun RvSkeletonDisc(size: Dp = RvSize.rowDisc) {
    Box(Modifier.size(size).testTag(RvPart.SKELETON_DISC).clip(CircleShape).background(Rv.colors.track))
}

/** A text's bar: a pill [RvSkeletonSize.BAR] of [style]'s size tall, in the progress track's neutral. */
@Composable
private fun Bar(style: TextStyle, modifier: Modifier) {
    val tall = with(LocalDensity.current) { style.fontSize.toDp() } * RvSkeletonSize.BAR
    Box(modifier.height(tall).clip(CircleShape).background(Rv.colors.track))
}

/**
 * Revolut's light shimmer over a skeleton's parts: a soft band crosses the page from start to end once each
 * [RvMotion.BUSY_MS], fading what it passes toward the surface under it by [RvAlpha.SHIMMER] at its middle. The band is
 * placed on the page, not on each part, and every part reads one pass ([LocalRvSweep], else the frame clock's own), so the
 * hero's and every card's parts shimmer as one light. At animator scale 0 the skeleton stands still; [LocalRvShimmerAt]
 * holds the band where it says. Draw-phase only: the band's gradient is made once for the page's width, and a frame
 * moves it and redraws this layer alone, allocating nothing.
 */
@Composable
internal fun Modifier.rvShimmer(): Modifier {
    val still = LocalRvShimmerAt.current
    if (still == null && Rv.motion.instant) return this
    val shared = LocalRvSweep.current
    val sweep = still?.let { at -> { at } } ?: shared ?: rvSweep()
    // Where this layer starts on the page, and the page's width, in px.
    val page = remember { FloatArray(2) }
    return onPlaced {
        page[0] = it.positionInRoot().x
        page[1] = it.findRootCoordinates().size.width.toFloat()
    }
        .preferredFrameRate(FrameRateCategory.Normal)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val band = page[1] * RvSkeletonSize.BAND
            // Only the mask's alpha counts: it takes that much of the parts away where the band is.
            val mask = Brush.horizontalGradient(SHIMMER_MASK, startX = 0f, endX = band)
            onDrawWithContent {
                drawContent()
                val start = -band + sweep() * (page[1] + band) - page[0]
                translate(left = start) { drawRect(mask, Offset(-start, 0f), size, blendMode = BlendMode.DstOut) }
            }
        }
}

/**
 * The pass every shimmer under it reads, so a page's parts share one ([RvShimmerPass]); null where none is set, and each
 * part then reads the frame clock itself, which still keeps them in step.
 */
private val LocalRvSweep = staticCompositionLocalOf<(() -> Float)?> { null }

/** One shimmer pass for [content]'s skeleton parts, unless it stands still or a pass is already set above it. */
@Composable
private fun RvShimmerPass(content: @Composable () -> Unit) {
    if (LocalRvShimmerAt.current != null || Rv.motion.instant || LocalRvSweep.current != null) {
        content()
    } else {
        CompositionLocalProvider(LocalRvSweep provides rvSweep(), content = content)
    }
}

/**
 * The shimmer's pass, 0 to 1 across the page, once each [RvMotion.BUSY_MS], read where it is drawn. It is the frame
 * clock's time in the pass, so every pass on the screen, wherever it started, is at the same point.
 */
@Composable
private fun rvSweep(): () -> Float {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withInfiniteAnimationFrameMillis { now.longValue = it } }
    return remember { { RvMotion.Standard.transform((now.longValue % RvMotion.BUSY_MS).toFloat() / RvMotion.BUSY_MS) } }
}

/** The band's mask: clear at its edges, [RvAlpha.SHIMMER] at its middle. */
private val SHIMMER_MASK = listOf(Color.Transparent, Color.Black.copy(alpha = RvAlpha.SHIMMER), Color.Transparent)

private const val TILES = 2
