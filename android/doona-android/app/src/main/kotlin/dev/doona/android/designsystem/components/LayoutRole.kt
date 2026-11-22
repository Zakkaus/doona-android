package dev.doona.android.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/**
 * What a component is on the page, so the spacing check can assert its rules by role instead of by position. The
 * property has no meaning for accessibility services and does not change how a component draws or lays out.
 */
internal enum class LayoutRole {
    // The old components' roles, which the old screens' spacing rules (U1) check.
    Sheet,
    TopBar,
    HubBar,
    Card,
    Row,
    Control,
    Button,
    Value,
    Chevron,
    Dot,
    Bar,
    Field,

    // The design system's (the RV sample's) roles, whose geometry differs from U1's: a card whose rows pad themselves, a
    // list or settings row, an in-card header or section title, a switch or radio, the segmented control, a chip, a tag,
    // the drawn chevron, a flag, a chart or map, the hero figure, and the page's chrome (bars, back disc, capsule).
    ListCard,
    ListRow,
    Heading,
    Toggle,
    Segmented,
    Chip,
    Tag,
    Mark,
    Flag,
    Chart,
    Figure,
    Chrome,
}

internal val LayoutRoleKey = SemanticsPropertyKey<LayoutRole>("DoonaLayoutRole")

internal fun Modifier.layoutRole(role: LayoutRole, mergeDescendants: Boolean = false): Modifier = semantics(mergeDescendants) { this[LayoutRoleKey] = role }
