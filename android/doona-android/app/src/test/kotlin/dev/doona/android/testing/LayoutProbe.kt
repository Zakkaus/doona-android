package dev.doona.android.testing

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey

/**
 * One layout node of the composition: its outer box in dp, in root coordinates (modifiers such as padding are inside
 * it), its children in composition order, and the semantics node of the same layout node when it has one.
 */
class LayoutBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    private val semantics: SemanticsNode?,
    private val painted: Boolean,
    val filled: Boolean,
    val children: List<LayoutBox>,
    private val density: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val role: LayoutRole? get() = semantics?.config?.getOrNull(LayoutRoleKey)
    val text: String? get() = semantics?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    val actionable get() = semantics?.config?.contains(SemanticsActions.OnClick) == true

    /** The part of the node a pointer reaches: its box grown to Compose's 48 dp minimum, in dp. */
    val touchSize: Pair<Float, Float>?
        get() = semantics?.touchBoundsInRoot?.let { (it.width / density) to (it.height / density) }

    /** A node with nothing to show: a Spacer, or a wrapper holding only such nodes. */
    val blank: Boolean get() = semantics == null && !painted && children.all { it.blank }

    /** Children that draw something or hold content, in order. */
    val content: List<LayoutBox> get() = children.filter { !it.blank && it.width > 0 && it.height > 0 }

    /** The nodes with no children that show something: glyphs, text, painted boxes. */
    val leaves: List<LayoutBox>
        get() = if (children.isEmpty()) listOf(this).filter { !blank && width > 0 } else children.flatMap { it.leaves }

    fun descendants(): Sequence<LayoutBox> = sequenceOf(this) + children.asSequence().flatMap { it.descendants() }

    /** Consecutive children stacked top to bottom with their vertical gaps; empty when the node is not a column. */
    fun columnGaps(): List<Triple<LayoutBox, LayoutBox, Float>> {
        val items = content
        val stacked = items.zipWithNext().all { (a, b) -> b.top >= a.bottom - EPS }
        return if (items.size < 2 || !stacked) emptyList() else items.zipWithNext().map { (a, b) -> Triple(a, b, b.top - a.bottom) }
    }

    override fun toString() = "[%.1f,%.1f %.1fx%.1f %s]".format(left, top, width, height, text ?: role ?: "")

    companion object {
        const val EPS = 0.01f
    }
}

private val PAINTING = setOf("BackgroundElement", "BorderModifierNodeElement", "DrawBehindElement", "PainterElement")

/**
 * Reads the layout tree of [compose]'s content. Compose's public API reaches a layout node's parent but not its
 * children, so the children come from `LayoutNode.getChildrenInfo()`, a public method of an internal interface.
 */
fun ComposeContentTestRule.layoutTree(): LayoutBox {
    val semantics = HashMap<Int, SemanticsNode>()
    fun collect(node: SemanticsNode) {
        semantics[node.id] = node
        node.children.forEach(::collect)
    }
    collect(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    return box(onRoot().fetchSemanticsNode().layoutInfo, null, semantics)
}

@Suppress("UNCHECKED_CAST")
private fun children(info: LayoutInfo): List<LayoutInfo> = info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>

/** The outermost coordinates of a layout node: those just inside its parent's content box. */
private fun outer(info: LayoutInfo, parent: LayoutInfo?): LayoutCoordinates {
    var c = info.coordinates
    while (true) {
        val up = c.parentCoordinates ?: return c
        if (up == parent?.coordinates) return c
        c = up
    }
}

private fun box(info: LayoutInfo, parent: LayoutInfo?, semantics: Map<Int, SemanticsNode>): LayoutBox {
    val coordinates = outer(info, parent)
    val density = info.density.density
    val at = coordinates.positionInRoot()
    return LayoutBox(
        left = at.x / density,
        top = at.y / density,
        right = (at.x + coordinates.size.width) / density,
        bottom = (at.y + coordinates.size.height) / density,
        semantics = semantics[info.semanticsId],
        painted = info.getModifierInfo().any { it.modifier.javaClass.simpleName in PAINTING },
        filled = info.getModifierInfo().any { it.modifier.javaClass.simpleName == "BackgroundElement" },
        children = children(info).map { box(it, info, semantics) },
        density = density,
    )
}
