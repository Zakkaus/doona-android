package dev.doona.android.feature.subscriptions

import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.textclassifier.TextClassifier

/**
 * The clipboard as 新增訂閱 and 新增節點 use it. [mayHoldLink] reads only the clip's description, which Android does not report to
 * the user; [link] reads the content, which Android announces, so it runs only when the user taps the row.
 */
interface ClipboardLinks {
    fun mayHoldLink(): Boolean

    /** The clip's text when it is a subscription link, else null. */
    fun link(): String?

    /** Whether the clip may hold text, from its description only: 新增節點 takes share links, which are no web address. */
    fun mayHoldText(): Boolean = mayHoldLink()

    /** The clip's text, trimmed, whatever it holds; read only when the user taps the row. */
    fun text(): String? = link()
}

/**
 * [ClipboardLinks] over Android's clipboard. A clip may hold a link when it is a URI list, or text that the system's
 * classifier found a URL in, or text it has not classified (a clip too long to classify, or a classifier that is off).
 */
class AndroidClipboardLinks(private val clipboard: ClipboardManager) : ClipboardLinks {
    override fun mayHoldLink(): Boolean {
        val description = clipboard.primaryClipDescription ?: return false
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return true
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return false
        return description.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE ||
            description.getConfidenceScore(TextClassifier.TYPE_URL) > 0f
    }

    override fun link(): String? = text()?.takeIf { urlProblem(it) == null }

    override fun mayHoldText(): Boolean {
        val description = clipboard.primaryClipDescription ?: return false
        return description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
    }

    override fun text(): String? = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { (it.text ?: it.uri)?.toString()?.trim()?.ifEmpty { null } }
}
