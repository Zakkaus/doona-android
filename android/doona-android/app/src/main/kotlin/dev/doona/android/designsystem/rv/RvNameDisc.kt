package dev.doona.android.designsystem.rv

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp

/**
 * The row's leading disc: the name's recognised mark as our flag or glyph ([current] badges a flag as the chosen node's),
 * else what the row leads with today ([flag], the node's place).
 */
@Composable
internal fun RvNameDisc(mark: RvNameMark?, flag: String?, current: Boolean = false, size: Dp = RvSize.rowDisc) {
    when (mark) {
        is RvNameMark.Flag -> RvFlag(mark.code, size, current = current)
        is RvNameMark.Icon -> RvIconDisc(mark.glyph, size = size, current = current)
        null -> RvFlag(flag, size, current = current)
    }
}

/** What a name with nothing left but its emoji reads: the flag's country ([place]), or the brand or group. */
@Composable
internal fun rvMarkName(mark: RvNameMark?, place: String): String = rvMarkLabel(mark)?.let { stringResource(it) } ?: place

/** The string [rvMarkName] reads for [mark]: the flag's country, or the brand or group; null for no mark or an unknown flag. */
@StringRes
internal fun rvMarkLabel(mark: RvNameMark?): Int? = when (mark) {
    is RvNameMark.Flag -> RvPlace.entries.firstOrNull { it.flag.equals(mark.code, ignoreCase = true) }?.label
    is RvNameMark.Icon -> mark.name
    null -> null
}
