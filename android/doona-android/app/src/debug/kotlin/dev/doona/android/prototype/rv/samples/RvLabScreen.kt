package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.prototype.rv.settings.RvSettingsCard
import dev.doona.android.prototype.rv.settings.RvSettingsSpace
import dev.doona.android.prototype.rv.settings.RvSettingsSwitchRow

/**
 * 實驗室 (owner, 2026-11-25: experimental options go on a page of their own in 設定), a page of 設定 in 關於's form: the
 * large title, a card of switch rows, and a footnote that the options may change or go away. Its one item for now is
 * 效能模式 ([performance]), which takes effect the next time the app starts.
 */
@Composable
internal fun RvLabScreen(performance: Boolean, onBack: () -> Unit = {}) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = sampleText("實驗室", "实验室", "Lab")
    var on by remember { mutableStateOf(performance) }
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsCard {
                RvSettingsSwitchRow(
                    sampleText("效能模式", "性能模式", "Performance mode"),
                    R.drawable.rv_sym_speed,
                    on,
                    { on = it },
                    sampleText(
                        "用較多 CPU 與電量換取速度；下次開啟 App 生效",
                        "用更多 CPU 和电量换取速度；下次打开 App 生效",
                        "Uses more CPU and battery for speed. Takes effect next time the app opens.",
                    ),
                )
            }
            RvFootnote(sampleText("這些選項可能變動或移除。", "这些选项可能变动或移除。", "These options may change or go away."), inset = RvSpace.gutter - RvSettingsSpace.margin)
        }
    }
}

/** The sample's words in zh-TW, zh-CN and English; the strings of a decided feature come with its port. */
@Composable
internal fun sampleText(zhTw: String, zhCn: String, en: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        locale.language != "zh" -> en
        locale.country == "CN" -> zhCn
        else -> zhTw
    }
}
