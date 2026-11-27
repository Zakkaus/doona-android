package dev.doona.android.prototype.rv.diag

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 日誌檢視's level map picks the lowest level shown, as the web's row headers do: the current minimum's row is selected,
 * and picking 警告 moves the chip to 警告及以上, drops the 資訊 row from the map and the counts, and keeps the rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h2400dp-notnight-xhdpi")
class RvLogsMinimumTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun aRowSetsTheMinimumAndTheChipFollows() {
        compose.rvContent(dark = false, fontScale = 1f, gesture = true) { RvLogsSample() }
        compose.onNodeWithText("資訊及以上").assertExists()
        compose.onNodeWithContentDescription("資訊：目前的最低等級").assertIsSelected()
        compose.onNodeWithContentDescription("顯示警告及以上").performClick()
        compose.onNodeWithText("警告及以上").assertExists()
        compose.onNodeWithContentDescription("警告：目前的最低等級").assertIsSelected()
        compose.onNodeWithContentDescription("顯示錯誤及以上").assertExists()
        compose.onNodeWithContentDescription("顯示資訊及以上").assertDoesNotExist()
        compose.onNodeWithText("4 筆").assertDoesNotExist()
    }
}
