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
    val painted: Boolean,
    val filled: Boolean,
    /** Whether the node lays its children out top to bottom (a Column), whatever their geometry. */
    val vertical: Boolean,
    val children: List<LayoutBox>,
    private val density: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    internal val role: LayoutRole? get() = semantics?.config?.getOrNull(LayoutRoleKey)
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

    /** The box that clips [scrolling], which is this node's descendant: the viewport a scrolling column moves in. */
    fun viewportOf(scrolling: LayoutBox): LayoutBox = descendants().first { parent -> parent.children.any { it === scrolling } }

    /** The nodes with no children that draw an image or a glyph: an icon, not a box or a text. */
    val glyph: Boolean get() = children.isEmpty() && painted && !filled && text == null

    /**
     * Consecutive children of a column with the signed gap between them, negative when they overlap; empty when the node
     * is not a column. A column is recognised by its layout, not by where its children ended up, so an overlap is a gap
     * of less than zero and not the end of the column.
     */
    fun columnGaps(): List<Triple<LayoutBox, LayoutBox, Float>> =
        if (vertical) content.zipWithNext().map { (a, b) -> Triple(a, b, b.top - a.bottom) } else emptyList()

    /** The same for the children of a row: the signed gap from one child's right edge to the next one's left. */
    fun rowGaps(): List<Triple<LayoutBox, LayoutBox, Float>> =
        if (vertical) emptyList() else content.sortedBy { it.left }.zipWithNext().map { (a, b) -> Triple(a, b, b.left - a.right) }

    override fun toString() = "[%.1f,%.1f %.1fx%.1f %s]".format(left, top, width, height, text ?: role ?: "")
}

private val PAINTING = setOf("BackgroundElement", "BorderModifierNodeElement", "DrawBehindElement", "PainterElement")

/**
 * Reads the layout tree of [compose]'s content. Compose's public API reaches a layout node's parent but not its
 * children or its layout policy, so they come from `LayoutNode.getChildrenInfo()` and `getMeasurePolicy()`, public
 * methods of an internal class, and from the class names of the policy and of the modifiers. Those are Compose
 * internals: when one of them changes, detection finds nothing, so the tree is checked to hold what every page holds
 * and the read fails with a message instead of letting every rule pass over an empty tree.
 */
fun ComposeContentTestRule.layoutTree(): LayoutBox {
    val semantics = HashMap<Int, SemanticsNode>()
    fun collect(node: SemanticsNode) {
        semantics[node.id] = node
        node.children.forEach(::collect)
    }
    collect(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    return box(onRoot().fetchSemanticsNode().layoutInfo, null, semantics).also(::requireDetection)
}

/** What any rendered tree has: nested layout nodes, a column, a painted node and a filled one. */
private fun requireDetection(root: LayoutBox) {
    val nodes = root.descendants().toList()
    val missing = buildList {
        if (nodes.size < 2) add("no layout node has children (LayoutNode.getChildrenInfo)")
        if (nodes.none { it.vertical }) add("no node has a ColumnMeasurePolicy (LayoutNode.getMeasurePolicy)")
        if (nodes.none { it.filled }) add("no node has a BackgroundElement modifier")
        if (nodes.none { it.painted }) add("no node has a painting modifier ($PAINTING)")
    }
    check(missing.isEmpty()) { "The layout probe detects nothing it must find, so Compose's internals changed:\n" + missing.joinToString("\n") }
}

@Suppress("UNCHECKED_CAST")
private fun children(info: LayoutInfo): List<LayoutInfo> = info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>

private fun isColumn(info: LayoutInfo): Boolean = info.javaClass.getMethod("getMeasurePolicy").invoke(info).javaClass.simpleName == "ColumnMeasurePolicy"

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
        vertical = isColumn(info),
        children = children(info).map { box(it, info, semantics) },
        density = density,
    )
}
