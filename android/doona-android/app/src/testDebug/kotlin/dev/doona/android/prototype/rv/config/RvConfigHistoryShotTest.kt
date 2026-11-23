package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Revisions and import (PV-f item 4), samples only: the nine applied revisions with the head in use
 * (`proto-rv-config-history-*`); an import refused at the 32-file / 8 MiB budget (`proto-rv-config-import-limit-*`);
 * and one staged with an include outside the picked file's directory (`proto-rv-config-import-escape-*`). Light and dark
 * hold the whole page; fs115 and fs200 the first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigHistoryShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyLight() = compose.rvShot("config-history", dark = false, before = { assertWhole() }) { History() }

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyDark() = compose.rvShot("config-history", dark = true, before = { assertWhole() }) { History() }

    @Test
    fun historyFs115() = compose.rvShot("config-history", dark = false, fontScale = RV_FS115) { History() }

    @Test
    fun historyFs200() = compose.rvShot("config-history", dark = false, fontScale = RV_FS200) { History() }

    @Test
    fun limitLight() = compose.rvShot("config-import-limit", dark = false) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitDark() = compose.rvShot("config-import-limit", dark = true) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitFs115() = compose.rvShot("config-import-limit", dark = false, fontScale = RV_FS115) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitFs200() = compose.rvShot("config-import-limit", dark = false, fontScale = RV_FS200) { Import(RvConfigHistoryData.limit) }

    @Test
    fun escapeLight() = compose.rvShot("config-import-escape", dark = false) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeDark() = compose.rvShot("config-import-escape", dark = true) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeFs115() = compose.rvShot("config-import-escape", dark = false, fontScale = RV_FS115) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeFs200() = compose.rvShot("config-import-escape", dark = false, fontScale = RV_FS200) { Import(RvConfigHistoryData.escape) }

    @Test
    fun aRevisionOpensItsDiff() {
        var opened = 0
        compose.setContent { Themed { RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {}, onRevision = { opened = it }) } }
        compose.onNodeWithText("第 6 版").performScrollTo().performClick()
        assertEquals(6, opened)
    }

    @Test
    fun anImportWithAnEscapingIncludeCannotGoOn() {
        compose.setContent { Themed { Import(RvConfigHistoryData.escape) } }
        compose.onNodeWithText("檢視變更").assertIsNotEnabled()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun History() = RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {})

    @Composable
    private fun Import(state: RvImportState) = RvConfigImportPage(state, onBack = {})

    private companion object {
        const val WHOLE_HISTORY = "+h1019dp"
    }
}
