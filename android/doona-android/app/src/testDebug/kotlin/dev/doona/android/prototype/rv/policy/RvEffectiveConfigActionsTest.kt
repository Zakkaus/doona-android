package dev.doona.android.prototype.rv.policy

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.R
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 檢視生效設定's 複製 and 匯出: the page's two rows call what they are given, and the preview binds them to the
 * sample's effective text, which goes to the clipboard as sensitive plain text and to a share sheet as `text/plain`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvEffectiveConfigActionsTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val sample = runBlocking { rvSampleEngine().effectiveConfig().getOrThrow().text }

    @Test
    fun theRowsCallTheirActionsAndNothingElse() {
        var copied = 0
        var exported = 0
        compose.rvContent(dark = false) {
            RvEffectiveConfigPageSample(RvPolicyState(), rvSampleSettings(), onBack = {}, onCopy = { copied++ }, onExport = { exported++ })
        }
        tap(R.string.rv_effective_copy)
        assertEquals(1 to 0, copied to exported)
        tap(R.string.rv_effective_export)
        assertEquals(1 to 1, copied to exported)
    }

    @Test
    fun copyPutsTheSampleTextOnTheClipboardAsSensitivePlainText() {
        preview()
        tap(R.string.rv_effective_copy)
        compose.waitForIdle()
        val clip = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull(clip)
        assertEquals(sample, clip!!.getItemAt(0).text.toString())
        assertEquals(1, clip.itemCount)
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertEquals(true, clip.description.extras?.getBoolean(SENSITIVE))
    }

    @Test
    fun exportOpensAShareSheetForTheSampleTextAsPlainText() {
        preview()
        tap(R.string.rv_effective_export)
        compose.waitForIdle()
        val sheet = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, sheet.action)
        assertEquals(rvText(R.string.rv_effective_title), sheet.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val send = sheet.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(sample, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(rvText(R.string.rv_effective_title), send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
    }

    private fun preview() = compose.rvContent(dark = false) {
        RvPolicyPreview(RvPolicyPreviewRoutes.EFFECTIVE, RvPolicyPreviewState(), rvSampleSettings(), push = {}, pop = {}, onTab = {})
    }

    /** The row with [label]; it is the page's last, so it is scrolled into view first. */
    private fun tap(label: Int) {
        compose.onNodeWithText(rvText(label)).performScrollTo().performClick()
    }

    private companion object {
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
