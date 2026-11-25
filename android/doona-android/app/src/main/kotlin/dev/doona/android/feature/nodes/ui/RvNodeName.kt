package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvNameDisc
import dev.doona.android.designsystem.rv.RvNameMark
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvMarkLabel
import dev.doona.android.designsystem.rv.rvMarkName
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RvNode

/*
 * A node's name as its row draws it (q-emoji1, 2026-11-25 14:35): a recognised emoji leaves the name and becomes the row's
 * disc, our flag or our mark; an unrecognised one stays in the name. Search, copying, the node's page and the profile keep
 * the raw name.
 */

/** The title of [node]'s row: its name without the emoji the disc draws, or, when nothing else is left, what the mark names. */
@Composable
internal fun rvNodeTitle(node: RvNode): String {
    val shown = remember(node.name) { rvShownName(node.name) }
    return shown.text.ifEmpty { rvMarkName(shown.mark, stringResource(node.place.label)) }
}

/**
 * Every row's [rvNodeTitle] outside composition, for the lists' 依名稱 (decision 2026-11-25 19:25: the name the row
 * draws), read with the screen's strings.
 */
@Composable
internal fun rememberRvNodeTitles(): (RvNode) -> String {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    return remember(resources, locale) {
        { node ->
            val shown = rvShownName(node.name)
            shown.text.ifEmpty { resources.getString(rvMarkLabel(shown.mark) ?: node.place.label) }
        }
    }
}

/** The disc of [node]'s row: the flag or mark its name's first recognised emoji became, else the flag of its place. */
@Composable
internal fun RvNodeDisc(node: RvNode, size: Dp, modifier: Modifier = Modifier, current: Boolean = false) {
    when (val mark = remember(node.name) { rvShownName(node.name) }.mark) {
        null -> RvFlag(node.place.flag, size, modifier, current = current)
        is RvNameMark.Flag -> RvFlag(mark.code, size, modifier, current = current)
        is RvNameMark.Icon -> RvNameDisc(mark, null, current, size)
    }
}

/**
 * A node row's second line: its [parts] (a mark, the protocol, the place) joined by the separator, the empty ones left
 * out, so a node whose details the core has not sent (no protocol yet) reads 其他, not 「 · 其他」.
 */
internal fun rvNodeLine(vararg parts: String?): String = parts.filterNot { it.isNullOrEmpty() }.joinToString(SEPARATOR)
