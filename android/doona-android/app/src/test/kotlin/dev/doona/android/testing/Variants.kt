package dev.doona.android.testing

/** One way to show a subject: the window, the theme and the font scale. Qualifiers are width, height, night and density. */
class Variant(val name: String, val qualifiers: String, val fontScale: Float = 1f) {
    override fun toString() = name
}

private const val WINDOW = "zh-rTW-w%ddp-h%ddp-%s-xhdpi"

/** The phone at 412 × 915 dp in zh-TW: light, dark, and light at the two font scales Android offers beyond normal. */
val Light = Variant("light", WINDOW.format(412, 915, "notnight"))
val Dark = Variant("dark", WINDOW.format(412, 915, "night"))
val FontScale115 = Variant("fs115", WINDOW.format(412, 915, "notnight"), 1.15f)
val FontScale200 = Variant("fs200", WINDOW.format(412, 915, "notnight"), 2f)

/** A tablet-width window: the content column stays at most 600 dp wide and centred. */
val Wide = Variant("wide", WINDOW.format(840, 915, "notnight"))

/** A phone turned on its side: 915 × 412 dp, so short that every screen scrolls. */
val Landscape = Variant("landscape", WINDOW.format(915, 412, "notnight"))

val PhoneVariants = listOf(Light, Dark, FontScale115, FontScale200)
val ScreenVariants = PhoneVariants + listOf(Wide, Landscape)

/** The same variant in a window [height] dp high, for subjects taller than a phone. */
fun Variant.tall(height: Int) = Variant(name, qualifiers.replace(Regex("-h\\d+dp-"), "-h${height}dp-"), fontScale)

/** The component gallery pages are long: 1300 dp shows the longest whole, and the window grows with the font scale. */
val GalleryVariants = PhoneVariants.map { it.tall((1300 * it.fontScale).toInt()) }
