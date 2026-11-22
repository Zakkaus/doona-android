package dev.doona.android.testing

/** One way to show a subject: the window, the theme and the font scale. */
class Variant(val name: String, val qualifiers: String, val fontScale: Float = 1f) {
    override fun toString() = name
}

private const val PHONE = "zh-rTW-w412dp-h915dp-%s-xhdpi"

/** The phone at 412 × 915 dp in zh-TW: light, dark, and light at the two font scales Android offers beyond normal. */
val Light = Variant("light", PHONE.format("notnight"))
val Dark = Variant("dark", PHONE.format("night"))
val FontScale115 = Variant("fs115", PHONE.format("notnight"), 1.15f)
val FontScale200 = Variant("fs200", PHONE.format("notnight"), 2f)

val PhoneVariants = listOf(Light, Dark, FontScale115, FontScale200)
