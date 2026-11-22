package dev.doona.android.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/**
 * What a component is on the page, so the spacing check can assert its rules by role instead of by position. The
 * property has no meaning for accessibility services and does not change how a component draws or lays out.
 */
internal enum class LayoutRole { Sheet, TopBar, HubBar, Card, Row, Control, Button, Value, Chevron, Dot, Bar }

internal val LayoutRoleKey = SemanticsPropertyKey<LayoutRole>("DoonaLayoutRole")

internal fun Modifier.layoutRole(role: LayoutRole, mergeDescendants: Boolean = false): Modifier = semantics(mergeDescendants) { this[LayoutRoleKey] = role }
