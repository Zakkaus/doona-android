package dev.doona.android.testing

/** One way to show a subject: the window, the theme and the font scale. Qualifiers are width, height, night and density. */
class Variant(val name: String, val qualifiers: String, val fontScale: Float = 1f) {
    override fun toString() = name
}

private const val WINDOW = "zh-rTW-w%ddp-h%ddp-%s-xhdpi"

/** The phone at 412 × 915 dp in zh-TW: light, dark, and 200% font in each. */
val Light = Variant("light", WINDOW.format(412, 915, "notnight"))
val Dark = Variant("dark", WINDOW.format(412, 915, "night"))
val FontScale200 = Variant("fs200", WINDOW.format(412, 915, "notnight"), 2f)
val FontScale200Dark = Variant("fs200-dark", WINDOW.format(412, 915, "night"), 2f)

/** A phone turned on its side: 915 × 412 dp, so short that every screen scrolls. */
val Landscape = Variant("landscape", WINDOW.format(915, 412, "notnight"))

/** The same variant in a window [height] dp high, for subjects taller than a phone. */
fun Variant.tall(height: Int) = Variant(name, qualifiers.replace(Regex("-h\\d+dp-"), "-h${height}dp-"), fontScale)

/** The design system's gallery pages: light and dark at font scales 1.0 and 2.0, in a window that grows with the font scale. */
val RvGalleryVariants = listOf(Light, Dark, FontScale200, FontScale200Dark).map { it.tall((2000 * it.fontScale).toInt()) }
