package dev.doona.android.prototype.rv.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** A figure split so its unit can be set small: `1.84` and ` GB`. */
internal class RvAmount(val number: String, val unit: String) {
    override fun toString() = number + unit
}

/** A duration in whole hours and the minutes after them, as `1 小時 42 分` writes it. */
internal data class RvDuration(val hours: Int, val minutes: Int)

/**
 * How every page writes a figure. Bytes and rates are decimal SI (kB, MB, GB, as Android sizes files) to three
 * significant digits, so `1.84 GB`, `233 MB`, `301 kB/s`, and a count under a kilobyte is whole bytes. Durations are
 * whole hours and minutes; dates are the locale's short date (an end date drops an unambiguous year) and times are
 * `HH:mm`, all in the device's zone.
 */
internal class RvFormat(val locale: Locale) {
    private val percents = NumberFormat.getPercentInstance(locale)
    private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
    private val monthDays = DateTimeFormatter.ofPattern(withoutYear(shortPattern(locale)), locale)

    fun bytes(bytes: Long): RvAmount = scaled(BigDecimal.valueOf(bytes), "")

    /** A rate; one that is not a number or infinite (no time has passed) is written as none. */
    fun rate(bytesPerSecond: Double): RvAmount = scaled(BigDecimal.valueOf(if (bytesPerSecond.isFinite()) bytesPerSecond else 0.0), PER_SECOND)

    /** [bytes] written in the unit [like] has, without the unit: the `61.7` of `61.7 / 200 GB`. */
    fun bytesIn(bytes: Long, like: RvAmount): String {
        val unit = UNITS.indexOf(like.unit.removeSuffix(PER_SECOND)).coerceAtLeast(0)
        return digits(BigDecimal.valueOf(bytes).movePointLeft(STEP * unit), unit)
    }

    fun duration(seconds: Long): RvDuration = RvDuration((seconds / SECONDS_PER_HOUR).toInt(), (seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt())

    fun percent(fraction: Double): String = percents.format(fraction)

    fun date(epochMs: Long): String = dates.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** An end date: without its year when it falls within the year after [nowEpochMs], so the year is never ambiguous. */
    fun expiry(epochMs: Long, nowEpochMs: Long): String =
        if (epochMs in nowEpochMs until nowEpochMs + MS_PER_YEAR) monthDays.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())) else date(epochMs)

    fun time(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** A second's time of day, for a scrubbed sample: `10:23:31`. */
    fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** [value] in the largest unit that keeps it under a thousand once rounded, so 999.6 MB is `1.00 GB`; a negative one alike. */
    private fun scaled(value: BigDecimal, suffix: String): RvAmount {
        var unit = 0
        var scaled = value
        while (unit < UNITS.lastIndex && significant(scaled).abs() >= THOUSAND) {
            scaled = scaled.movePointLeft(STEP)
            unit++
        }
        return RvAmount(digits(scaled, unit), UNITS[unit] + suffix)
    }

    /** Whole bytes, else three significant digits: two decimals under 10, one under 100, none above. */
    private fun digits(value: BigDecimal, unit: Int): String {
        val rounded = if (unit == 0) value.setScale(0, RoundingMode.HALF_UP) else significant(value)
        val decimals = when {
            unit == 0 || rounded.abs() >= HUNDRED -> 0
            rounded.abs() >= TEN -> 1
            else -> 2
        }
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(rounded.setScale(decimals, RoundingMode.HALF_UP))
    }

    private fun significant(value: BigDecimal): BigDecimal = if (value.signum() == 0) value else value.round(MathContext(DIGITS, RoundingMode.HALF_UP))

    private companion object {
        fun shortPattern(locale: Locale): String =
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)

        /** The locale's short date pattern without its year and the separator beside it: `y/M/d` and `M/d/yy` give `M/d`. */
        fun withoutYear(pattern: String): String = pattern.replace(YEAR, "").trim()

        val YEAR = Regex("y+[^A-Za-z]*|[^A-Za-z]*y+")
        const val MS_PER_YEAR = 365L * 24 * 3_600_000
        val UNITS = listOf(" B", " kB", " MB", " GB", " TB")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val THOUSAND: BigDecimal = BigDecimal.valueOf(1_000)
        val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
        val TEN: BigDecimal = BigDecimal.TEN
        const val PER_SECOND = "/s"
        const val STEP = 3
        const val DIGITS = 3
        const val SECONDS_PER_HOUR = 3_600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
