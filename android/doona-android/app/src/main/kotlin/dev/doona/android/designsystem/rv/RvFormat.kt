package dev.doona.android.designsystem.rv

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.pow

/** A figure split so its unit can be set small: `1.84` and ` GB`. */
internal data class RvAmount(val number: String, val unit: String) {
    override fun toString() = number + unit

    /** The amount where text wraps: a no-break space before its unit, so it breaks before `5.63 MB/s`, not inside it. */
    fun unbroken(): String = number + unit.replace(' ', NO_BREAK_SPACE)

    private companion object {
        const val NO_BREAK_SPACE = '\u00A0'
    }
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

    /** A size the core states in binary units, such as its download bound: `8 MiB`. */
    fun mebibytes(bytes: Long): RvAmount = RvAmount(BigDecimal.valueOf(bytes).divide(MEBIBYTE).stripTrailingZeros().toPlainString(), MIB)

    /** A rate; one that is not a number or infinite (no time has passed) is written as none. */
    fun rate(bytesPerSecond: Double): RvAmount = scaled(BigDecimal.valueOf(finite(bytesPerSecond)), PER_SECOND)

    /**
     * A live rate written in [unit] (an index of B/s, kB/s, MB/s …, from [rateUnit]), always to one decimal, so the
     * number of digits changes only at a new leading one: `0.0 kB/s` … `999.9 kB/s`, `0.9 MB/s` in a held unit.
     */
    fun rate(bytesPerSecond: Double, unit: Int): RvAmount =
        RvAmount(tenths(BigDecimal.valueOf(finite(bytesPerSecond)).movePointLeft(STEP * unit)), UNITS[unit] + PER_SECOND)

    /** A live rate with no unit held yet, as [rate] in [rateUnit]'s unit: a scrubbed second, written as the live one is. */
    fun liveRate(bytesPerSecond: Double): RvAmount = rate(bytesPerSecond, rateUnit(bytesPerSecond, held = -1))

    /**
     * The unit a live rate shown in [held] (-1 for none yet) is written in next, kB/s at least: up as soon as it rounds to
     * a thousand at one decimal, but down only once it is under [UNIT_HOLD] of [held]'s step (below 900 kB/s, not 1000),
     * so a rate hovering at a step does not change its unit back and forth.
     */
    fun rateUnit(bytesPerSecond: Double, held: Int): Int {
        val value = BigDecimal.valueOf(finite(bytesPerSecond))
        var natural = KILO
        while (natural < UNITS.lastIndex && value.movePointLeft(STEP * natural).setScale(1, RoundingMode.HALF_UP).abs() >= THOUSAND) natural++
        var unit = held.coerceAtMost(UNITS.lastIndex)
        while (unit > natural && value.abs() < UNIT_HOLD.movePointRight(STEP * unit)) unit--
        return maxOf(unit, natural)
    }

    /**
     * The widest a live rate is written in each of its units, `999.9 kB/s` … `999.9 TB/s`: a place showing a rate that
     * changes every second fits all of them, so its size holds as the rate moves.
     */
    fun widestRates(): List<RvAmount> = (KILO..UNITS.lastIndex).map { unit -> rate(WIDEST_LIVE * THOUSAND.toDouble().pow(unit), unit) }

    /**
     * [used] as the used side of `used/total`, as doona web writes the pair: without its unit when [total] has the same
     * one (the `61.7` of `61.7/200 GB`), with it when not (`461 GB/1.1 TB`).
     */
    fun usedOf(used: Long, total: RvAmount): String = bytes(used).let { if (it.unit == total.unit) it.number else it.toString() }

    fun duration(seconds: Long): RvDuration = RvDuration((seconds / SECONDS_PER_HOUR).toInt(), (seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt())

    fun percent(fraction: Double): String = percents.format(fraction)

    fun date(epochMs: Long): String = dates.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** A calendar [day], such as a release's, which is the same day in every time zone. */
    fun date(day: LocalDate): String = dates.format(day)

    /** An end date: without its year when it falls within the year after [nowEpochMs], so the year is never ambiguous. */
    fun expiry(epochMs: Long, nowEpochMs: Long): String =
        if (epochMs in nowEpochMs until nowEpochMs + MS_PER_YEAR) monthDays.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())) else date(epochMs)

    fun time(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** A second's time of day, for a scrubbed sample: `10:23:31`. */
    fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** [value] in the largest unit that keeps it under a thousand once rounded, so 999.6 MB is `1.00 GB`; a negative one alike. */
    private fun scaled(value: BigDecimal, suffix: String): RvAmount {
        val unit = unitOf(value)
        return RvAmount(digits(value.movePointLeft(STEP * unit), unit), UNITS[unit] + suffix)
    }

    /** The largest unit that keeps [value] under a thousand once rounded. */
    private fun unitOf(value: BigDecimal): Int {
        var unit = 0
        var scaled = value
        while (unit < UNITS.lastIndex && significant(scaled).abs() >= THOUSAND) {
            scaled = scaled.movePointLeft(STEP)
            unit++
        }
        return unit
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

    private val oneDecimal by lazy(LazyThreadSafetyMode.NONE) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            isGroupingUsed = false
        }
    }

    private fun tenths(value: BigDecimal): String = oneDecimal.format(value.setScale(1, RoundingMode.HALF_UP))

    private fun significant(value: BigDecimal): BigDecimal = if (value.signum() == 0) value else value.round(MathContext(DIGITS, RoundingMode.HALF_UP))

    private companion object {
        fun finite(value: Double) = if (value.isFinite()) value else 0.0

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
        val MEBIBYTE: BigDecimal = BigDecimal.valueOf(1L shl 20)
        const val MIB = " MiB"
        val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
        val TEN: BigDecimal = BigDecimal.TEN
        const val PER_SECOND = "/s"
        val UNIT_HOLD: BigDecimal = BigDecimal("0.9")
        const val WIDEST_LIVE = 999.9
        const val KILO = 1
        const val STEP = 3
        const val DIGITS = 3
        const val SECONDS_PER_HOUR = 3_600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
