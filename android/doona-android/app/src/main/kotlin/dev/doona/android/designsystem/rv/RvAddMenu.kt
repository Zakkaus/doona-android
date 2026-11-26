package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A page's bottom 新增 that adds one of several kinds: the [RvPrimaryButton] [label] opens [RvMenu] of [items] over it
 * (the menu flips above, as the button stands at the page's foot), and a pick runs the item. 全部規則 adds 規則 and
 * 服務規則 with it (owner, q-service-rules 2026-11-26); 訂閱 adds 訂閱 and 節點. One item needs no menu: the button runs it.
 */
@Composable
internal fun RvAddMenuButton(label: String, items: List<RvMenuItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        RvPrimaryButton(label, { if (items.size == 1) items.single().onClick() else open = true })
        RvMenu(open, { open = false }, items)
    }
}
