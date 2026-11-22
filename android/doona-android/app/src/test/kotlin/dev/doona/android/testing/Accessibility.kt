package dev.doona.android.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.checkRoboAccessibility

/**
 * The accessibility checks of a rendered node: ATF's, through Roborazzi, then [unnamedActions] on its merged semantics.
 * ATF alone passes an unnamed action whose content has a semantics node of its own (a clipped or filled box, an Image):
 * Compose hands that action to ATF as a plain View with no description and children, and ATF's speakable-text check lets
 * it through. An empty label on [dev.doona.android.designsystem.components.DoonaChartTools] or
 * [dev.doona.android.designsystem.components.DoonaToolDisc] passed ATF that way; an icon action with a bare Material
 * Icon in it did not.
 */
fun SemanticsNodeInteraction.checkAccessibility() {
    checkRoboAccessibility()
    val unnamed = unnamedActions(fetchSemanticsNode())
    if (unnamed.isNotEmpty()) throw AssertionError("actions a screen reader cannot name:\n" + unnamed.joinToString("\n"))
}

/**
 * Every node under [root] (merged, so a row's text counts for the row) that a screen reader can click but not name: it
 * has a click action and no content description, text, field text or field hint that is not blank.
 */
internal fun unnamedActions(root: SemanticsNode): List<String> = generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }
    .flatten()
    .filter { node ->
        val config = node.config
        val hidden = config.contains(SemanticsProperties.HideFromAccessibility)
        val names = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text, config.getOrNull(SemanticsProperties.HintText))
        config.contains(SemanticsActions.OnClick) && !hidden && names.all { it.isBlank() }
    }
    .map { "#${it.id} at ${it.boundsInRoot} (${it.config.getOrNull(SemanticsProperties.Role) ?: "no role"})" }
    .toList()
