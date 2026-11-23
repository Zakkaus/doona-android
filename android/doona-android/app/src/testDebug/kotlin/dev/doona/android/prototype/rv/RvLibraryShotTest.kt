package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RV_SAMPLE_LINK
import dev.doona.android.prototype.rv.gallery.RvGalleryBanners
import dev.doona.android.prototype.rv.gallery.RvGalleryEmpty
import dev.doona.android.prototype.rv.gallery.RvGalleryFields
import dev.doona.android.prototype.rv.gallery.RvGalleryMenuCard
import dev.doona.android.prototype.rv.subs.rvSubscriptionMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library parts added for the subscription and state pages, light and dark, each on the page under the status bar:
 * the text field in four states (`proto-rv-text-field-*`), the menu open over the subscription rows at the finger
 * (`proto-rv-menu-*`), three error banners (`proto-rv-banner-*`), the empty state (`proto-rv-empty-*`) and the confirm
 * sheet over a page (`proto-rv-confirm-sheet-*`, the phone's whole window); then what each does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLibraryShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+h640dp")
    fun fieldLight() = compose.rvShot("text-field", dark = false) { Page { RvGalleryFields() } }

    @Test
    @Config(qualifiers = "+h640dp")
    fun fieldDark() = compose.rvShot("text-field", dark = true) { Page { RvGalleryFields() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun menuLight() = compose.rvShot("menu", dark = false) { MenuOpen() }

    @Test
    @Config(qualifiers = "+h420dp")
    fun menuDark() = compose.rvShot("menu", dark = true) { MenuOpen() }

    @Test
    @Config(qualifiers = "+h640dp")
    fun bannerLight() = compose.rvShot("banner", dark = false) { Page { RvGalleryBanners() } }

    @Test
    @Config(qualifiers = "+h640dp")
    fun bannerDark() = compose.rvShot("banner", dark = true) { Page { RvGalleryBanners() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun emptyLight() = compose.rvShot("empty", dark = false) { Page { RvGalleryEmpty() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun emptyDark() = compose.rvShot("empty", dark = true) { Page { RvGalleryEmpty() } }

    @Test
    fun sheetLight() = compose.rvShot("confirm-sheet", dark = false) { SheetOpen() }

    @Test
    fun sheetDark() = compose.rvShot("confirm-sheet", dark = true) { SheetOpen() }

    @Test
    fun aLongPressOpensTheMenuAfterHalfASecond() {
        compose.setContent { Themed { Column { RvGalleryMenuCard() } } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(RvData.subscriptions.first().name).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HELD_SHORT)
        compose.onNodeWithText(UPDATE).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(HELD_LONG - HELD_SHORT)
        compose.onNodeWithText(UPDATE).assertExists()
        compose.onNodeWithText(RvData.subscriptions.first().name).performTouchInput { up() }
    }

    @Test
    fun pickingAnItemRunsItAndClosesTheMenu() {
        var deleted = 0
        compose.setContent { Themed { RvGalleryMenuCard(onDelete = { deleted++ }) } }
        compose.onNodeWithText(RvData.subscriptions.first().name).performTouchInput { longClick() }
        compose.onNodeWithText(DELETE).performClick()
        compose.waitForIdle()
        assertEquals(1, deleted)
        compose.onNodeWithText(DELETE).assertDoesNotExist()
    }

    @Test
    fun aScreenReaderGetsEachItemAsAnAction() {
        compose.setContent { Themed { RvGalleryMenuCard() } }
        val row = compose.onNodeWithText(RvData.subscriptions.first().name).fetchSemanticsNode()
        val labels = row.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf(UPDATE, EDIT, DELETE), labels)
    }

    @Test
    fun aFieldTakesTextAndMarksItsError() {
        var text by mutableStateOf("")
        compose.setContent {
            Themed { RvTextField(text, { text = it }, LINK, error = if (text.startsWith("ftp")) NOT_A_SUBSCRIPTION else null) }
        }
        compose.onNodeWithText(LINK).performTextInput("ftp://x")
        assertEquals("ftp://x", text)
        compose.onNodeWithText(LINK).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, NOT_A_SUBSCRIPTION))
    }

    @Test
    fun theSheetConfirmsOrDismisses() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            Themed {
                var shown by remember { mutableStateOf(true) }
                RvConfirmSheet(shown, TITLE, BODY, DELETE, CANCEL, onConfirm = { confirmed++ }, onDismiss = {
                    dismissed++
                    shown = false
                })
            }
        }
        compose.onNodeWithText(DELETE).assertHeightIsAtLeast(RvSize.touch).performClick()
        compose.onNodeWithText(CANCEL).assertHeightIsAtLeast(RvSize.touch).performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
        compose.onNodeWithText(TITLE).assertDoesNotExist()
    }

    @Test
    fun theEmptyStateOffersItsAction() {
        var added = 0
        compose.setContent { Themed { RvGalleryEmpty(onAdd = { added++ }) } }
        compose.onNodeWithText(ADD).assertHeightIsAtLeast(RvSize.touch).performClick()
        assertEquals(1, added)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    /** The page's colour, the parts on the gutter under the status bar. */
    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { content() } }
    }

    /** The subscription rows with the menu where a finger held the first row, as the popup places it. */
    @Composable
    private fun MenuOpen() {
        Box(Modifier.fillMaxSize()) {
            Page { RvGalleryMenuCard() }
            RvMenuPanel(rvSubscriptionMenu({}, {}, {}), onPick = {}, Modifier.offset(MENU_X, MENU_Y))
        }
    }

    /** A page with the subscription rows under the delete question, as the sheet's window draws it once it is in. */
    @Composable
    private fun SheetOpen() {
        val sub = RvData.subscriptions.first()
        Box(Modifier.fillMaxSize()) {
            Page { RvGalleryMenuCard() }
            val title = stringResource(R.string.rv_sub_delete_title, sub.name)
            RvSheetLayout({ 1f }, {}, title) {
                RvConfirmContent(
                    title,
                    pluralStringResource(R.plurals.rv_sub_delete_body, sub.nodes, sub.nodes),
                    stringResource(R.string.rv_menu_delete),
                    stringResource(R.string.rv_cancel),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
    }

    private companion object {
        val TOP = 72.dp

        /** Where a finger held the first row: a third of the way in, at the row's middle (its top 72 dp, 82 dp tall). */
        val MENU_X = 136.dp
        val MENU_Y = 113.dp
        const val HELD_SHORT = 450L
        const val HELD_LONG = 560L
        const val UPDATE = "更新"
        const val EDIT = "編輯"
        const val DELETE = "刪除"
        const val CANCEL = "取消"
        const val ADD = "新增訂閱"
        const val LINK = "訂閱連結"
        const val NOT_A_SUBSCRIPTION = "這個連結傳回的內容不是訂閱。"
        const val TITLE = "要刪除 Harbor Cloud 嗎？"
        const val BODY = "訂閱中的 48 個節點會一併移除。"
    }
}
