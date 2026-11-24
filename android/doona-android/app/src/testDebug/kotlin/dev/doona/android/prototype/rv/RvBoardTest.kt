package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.settings.RvAboutScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The design board's capture ([rvBoard]): the window and its layout in dp, the rulers' measurements on it, and a long
 * page's next screen; how a page's start is told from its parts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvBoardTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @get:Rule
    val folder = TemporaryFolder()

    /** 關於 fits its window: one capture, a pushed page, its cards 16 dp apart and every gap a token. */
    @Test
    fun aPushedPageIsOneWindowWithItsRulers() {
        val dir = capture("about") { RvAboutScreen(rvSampleVersions, onBack = {}) }
        assertTrue(File(dir, "about-light-1.png").isFile)
        assertFalse("關於 does not scroll", File(dir, "about-light-2.json").exists())
        val page = json(dir, "about-light-1")
        assertEquals("Title", page.getString("top"))
        assertEquals(412.0, page.getDouble("width"), 0.0)
        assertEquals(915.0, page.getDouble("height"), 0.0)
        val gaps = rules(page, "Gap")
        assertTrue("the cards' gaps are measured", gaps.count { abs(it.getDouble("actual") - 16.0) <= 0.5 } >= 2)
        assertTrue("every gap on 關於 is a token", gaps.all { it.getBoolean("token") })
        assertEquals("關於 keeps to the rulers", 0, page.getJSONArray("drift").length())
        assertTrue("the version rows' text has baselines", page.getJSONArray("baselines").length() >= 3)
        val tags = (0 until page.getJSONArray("parts").length()).map { page.getJSONArray("parts").getJSONObject(it).getString("tag") }
        assertTrue(RvPart.TITLE in tags && RvPart.CARD in tags && RvPart.ROW in tags)
    }

    /** 設定 is longer than its window: a second capture one screen down, measured without the header, then scrolled back. */
    @Test
    fun aLongPageGetsItsNextScreen() {
        val dir = capture("settings") { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) }
        val first = json(dir, "settings-light-1")
        val next = json(dir, "settings-light-2")
        assertEquals("Hero", first.getString("top"))
        assertEquals(0.0, first.getDouble("scrolled"), 0.0)
        assertTrue("the second screen is scrolled", next.getDouble("scrolled") > 100.0)
        assertTrue(rules(next, "Top").isEmpty())
        val firstCard = top(first, RvPart.CARD)
        val nextCard = top(next, RvPart.CARD)
        assertTrue("the content moved up", nextCard < firstCard || nextCard < 0.0)
    }

    @Test
    fun aTabHomeIsToldByItsFirstBlock() {
        val dir = capture("policy") { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        assertEquals("Tab", json(dir, "policy-light-1").getString("top"))
    }

    @Test
    fun aDetailPageIsToldByItsCentredHeader() {
        val dir = capture("connection") { RvConnectionDetail(RvPolicyData.connections[YOUTUBE], onBack = {}, onClose = {}, onNewRule = {}) }
        assertEquals("Header", json(dir, "connection-light-1").getString("top"))
    }

    @Test
    fun onlyLightAndDarkAreCaptured() {
        val dir = folder.newFolder("fs")
        compose.rvContent(dark = false) { RvAboutScreen(rvSampleVersions, onBack = {}) }
        compose.rvBoard("about", "fs200", dir)
        assertTrue(dir.list().isNullOrEmpty())
    }

    /** A gap with a search field in it is measured to the field and from it, each only held to the spacing tokens. */
    @Test
    fun aGapWithContentInItIsMeasuredToTheContent() {
        val gap = RvRule(RvRuleKind.Gap, "gap rv-title to rv-card at 211 dp", 68f, 24f, Rect(16f, 143f, 396f, 211f))
        val field = Rect(16f, 159f, 396f, 195f)
        val split = rvSplitGaps(listOf(gap), listOf(field, Rect(0f, 0f, 412f, 915f)))
        assertEquals(listOf(16f, 16f), split.map { it.actual })
        assertTrue(split.all { it.expected == null && it.token && !it.off })
        assertEquals(listOf(gap), rvSplitGaps(listOf(gap), listOf(Rect(16f, 300f, 396f, 400f))))
    }

    private fun capture(screen: String, content: @Composable () -> Unit): File {
        val dir = folder.newFolder(screen)
        compose.rvContent(dark = false, content = content)
        compose.rvBoard(screen, "light", dir)
        return dir
    }

    private fun json(dir: File, name: String) = JSONObject(File(dir, "$name.json").readText())

    private fun rules(page: JSONObject, kind: String): List<JSONObject> {
        val all = page.getJSONArray("rules")
        return (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("kind") == kind }
    }

    private fun top(page: JSONObject, tag: String): Double {
        val parts = page.getJSONArray("parts")
        return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.getString("tag") == tag }.minOf { it.getJSONArray("rect").getDouble(1) }
    }

    private companion object {
        /** YouTube's connection, as RvLayoutTest measures it. */
        const val YOUTUBE = 3
    }
}
