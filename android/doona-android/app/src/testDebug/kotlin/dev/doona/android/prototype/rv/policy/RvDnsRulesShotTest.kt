package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DNS 規則, the config's DNS query and response lists (no prototype; sample only), with the add and remove controls in
 * style A (the owner's open question): `dns-rules` the list with 編輯 in the bar; `dns-rules-editing` the edit mode (a
 * disc on each statement, the fallbacks' places empty, 新增規則 ending each card, 完成 at the bottom); `-confirm` the
 * question for `qname(suffix: corp.example)`; `-failed` a removal the phone could not stage; `-empty` each list's
 * fallback alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDnsRulesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun listLight() = shot("dns-rules", false) { Page(editing = false) }

    @Test
    @Config(qualifiers = WHOLE)
    fun listDark() = shot("dns-rules", true) { Page(editing = false) }

    @Test
    @Config(qualifiers = WHOLE_LIST_FS200)
    fun listFs200() = compose.rvShot("dns-rules", false, before = { assertWhole() }, fontScale = RV_FS200) { Page(editing = false) }

    @Test
    @Config(qualifiers = "en-w412dp-h991dp-notnight-xhdpi")
    fun listEnLight() = compose.rvShot("dns-rules", false, before = { assertWhole() }, english = true) { Page(editing = false) }

    @Test
    @Config(qualifiers = WHOLE_EDITING)
    fun editingLight() = shot("dns-rules-editing", false) { Page() }

    @Test
    @Config(qualifiers = WHOLE_EDITING)
    fun editingDark() = shot("dns-rules-editing", true) { Page() }

    @Test
    @Config(qualifiers = WHOLE_EDITING_FS200)
    fun editingFs200() = compose.rvShot("dns-rules-editing", false, before = { assertWhole() }, fontScale = RV_FS200) { Page() }

    @Test
    @Config(qualifiers = "en-w412dp-h1175dp-notnight-xhdpi")
    fun editingEnLight() = compose.rvShot("dns-rules-editing", false, before = { assertWhole() }, english = true) { Page() }

    @Test fun confirmLight() = confirm(false)

    @Test fun confirmDark() = confirm(true)

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedLight() = shot("dns-rules-failed", false) { Page(banner = failed()) }

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedDark() = shot("dns-rules-failed", true) { Page(banner = failed()) }

    @Test fun emptyLight() = shot("dns-rules-empty", false) { Page(lists = RvNativeSample.dnsListsEmpty) }

    @Test fun emptyDark() = shot("dns-rules-empty", true) { Page(lists = RvNativeSample.dnsListsEmpty) }

    @Test
    fun thePencilEntersEditModeAndTheSheetRemovesTheStatement() {
        var editing by mutableStateOf(false)
        val edits = mutableListOf<Boolean>()
        val removed = mutableListOf<String>()
        val edit = { on: Boolean ->
            edits += on
            editing = on
        }
        compose.setContent { Themed { RvDnsRulesPage(RvNativeSample.dnsLists, onBack = {}, edit = RvListEdit(editing, edit, onRemove = { removed += it })) } }
        compose.onNodeWithContentDescription("刪除 $CORP").assertDoesNotExist()
        compose.onNodeWithContentDescription("編輯").performClick()
        compose.onNodeWithContentDescription("刪除 其餘查詢").assertDoesNotExist()
        compose.onNodeWithContentDescription("刪除 $CORP").performScrollTo().performClick()
        compose.onNodeWithText("刪除").performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(listOf("Query:2"), removed)
        assertEquals(listOf(true, false), edits)
    }

    private fun confirm(dark: Boolean) = compose.rvShot(
        "dns-rules-confirm",
        dark,
        before = { onNodeWithContentDescription("刪除 $CORP").performScrollTo().performClick() },
        windows = true,
    ) { Page() }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(editing: Boolean = true, lists: RvDnsLists = RvNativeSample.dnsLists, banner: RvNotice? = null) =
        RvDnsRulesPage(lists, onBack = {}, edit = RvListEdit(editing), banner = banner)

    @Composable
    private fun failed() = RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_list_delete_failed_title, CORP),
        stringResource(R.string.rv_list_delete_failed_storage),
        stringResource(R.string.rv_retry),
        {},
        titleKeepWords = false,
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(rvSystemScheme(false), false, content)

    private companion object {
        const val CORP = "qname(suffix: corp.example)"

        /** Windows as tall as each whole page. */
        const val WHOLE = "+h969dp"
        const val WHOLE_LIST_FS200 = "+h1534dp"
        const val WHOLE_EDITING = "+h1153dp"
        const val WHOLE_EDITING_FS200 = "+h1998dp"
        const val WHOLE_FAILED = "+h1336dp"
    }
}
