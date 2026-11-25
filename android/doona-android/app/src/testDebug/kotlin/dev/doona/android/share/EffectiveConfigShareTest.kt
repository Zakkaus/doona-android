package dev.doona.android.share

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The effective config's two actions: what the clipboard holds and what the share sheet is asked to send. */
@RunWith(RobolectricTestRunner::class)
class EffectiveConfigShareTest {
    private val text = "# mode: Rule\nMATCH,DIRECT\n"

    @Test
    fun theClipIsExactlyTheTextAsSensitivePlainText() {
        val clip = effectiveConfigClip("Effective config", text)
        assertEquals(1, clip.itemCount)
        assertEquals(text, clip.getItemAt(0).text.toString())
        assertEquals("Effective config", clip.description.label.toString())
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertEquals(true, clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test
    fun theShareSheetSendsTheTextAsPlainTextWithoutAFile() {
        val sheet = effectiveConfigShareIntent("Effective config", text)
        assertEquals(Intent.ACTION_CHOOSER, sheet.action)
        assertEquals("Effective config", sheet.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val send = sheet.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Effective config", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNull(send.clipData)
    }
}
