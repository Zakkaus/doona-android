package dev.doona.android.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.doona.android.designsystem.rv.RvPullLook

/**
 * What 活動 shows of [current] while the pull or the picker ([look]) covers it: the value it showed last before it was
 * covered, so a second's traffic does not compose 活動 again under the blur; [current] is read only while uncovered, and
 * the page catches up as it comes back (plan 2.2, as the sample's `rememberShownSecond` holds its second).
 */
@Composable
internal fun <T> rememberHeldWhileCovered(look: RvPullLook, current: () -> T): T {
    val covered by remember(look) { derivedStateOf { look.progress() > 0f } }
    val held = remember { Held(current()) }
    // Read only while uncovered, so that a change under the cover does not compose the caller again.
    if (!covered) held.value = current()
    return held.value
}

private class Held<T>(var value: T)
