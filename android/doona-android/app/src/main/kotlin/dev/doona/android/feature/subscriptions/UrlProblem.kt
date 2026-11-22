package dev.doona.android.feature.subscriptions

import java.net.URI

/** Why a subscription URL is not sent: the form says so under the field. */
enum class UrlProblem { Empty, Invalid }

/**
 * A subscription is fetched over http or https, so anything else, and a URL with nothing after the scheme's `//`, is
 * refused here and not after the core's round trip. The host itself is the core's to judge: [URI.getHost] is null for
 * an internationalized name such as `bücher.example`, which is a good address. The text is trimmed first: a pasted
 * link often carries a trailing space or newline.
 */
fun urlProblem(text: String): UrlProblem? {
    val url = text.trim()
    if (url.isEmpty()) return UrlProblem.Empty
    val uri = runCatching { URI(url) }.getOrNull() ?: return UrlProblem.Invalid
    val http = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
    return if (http && !uri.rawAuthority.isNullOrEmpty()) null else UrlProblem.Invalid
}
