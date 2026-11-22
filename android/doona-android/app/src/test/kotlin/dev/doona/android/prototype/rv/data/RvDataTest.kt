package dev.doona.android.prototype.rv.data

import dev.doona.android.engine.Status
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.rvOutboundParts
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.stats.rvStatsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The invariants of the one RV dataset, on plain JVM: every figure that two places show agrees, checked on the strings
 * the pages show where the rule is about what is shown.
 */
class RvDataTest {
    // Dates and times are written in the device's zone; UTC keeps 3/15 the 15th on every machine.
    @get:Rule
    val utc = Utc()

    private val format = RvFormat(Locale.TAIWAN)
    private val session = RvData.session

    @Test
    fun sessionRunsFrom0842ForAnHourAndFortyTwoMinutes() {
        assertEquals("08:42", utc(session.startEpochMs))
        assertEquals("10:24", utc(RvData.nowEpochMs))
        assertEquals(6_120L, session.seconds)
        assertEquals(RvDuration(1, 42), format.duration(session.seconds))
        assertEquals("1 小時 42 分", zhString("strings_rv.xml", "rv_duration").replace("%1\$d", "1").replace("%2\$d", "42"))
        assertEquals(10.4f, RvData.nowHour, 1e-4f)
        assertEquals(11, RvData.today.down.size)
    }

    @Test
    fun averageTimesDurationIsTheTotalAsShown() {
        for ((average, total) in listOf(session.averageDown to session.downTotal, session.averageUp to session.upTotal)) {
            val shownAverage = shown(format.rate(average).toString().removeSuffix("/s"))
            val shownTotal = shown(format.bytes(total).toString())
            val implied = shownAverage.start * session.seconds..shownAverage.endInclusive * session.seconds
            assertTrue("$shownAverage × ${session.seconds} s against $shownTotal", implied.start <= shownTotal.endInclusive && shownTotal.start <= implied.endInclusive)
        }
    }

    @Test
    fun todaysPartsSumToItsTotalAsShown() {
        val day = RvData.today
        assertSumShown(listOf(day.downTotal, day.upTotal), day.total)
        // 活動's 今日用量 and 統計's 已用流量 read the same day.
        assertEquals(day.total, rvStatsSample().today.total)
    }

    @Test
    fun subscriptionsPartsSumToTheirTotalsAsShown() {
        val subscriptions = RvData.subscriptions
        assertEquals(listOf(RV_HARBOR, RV_LANTERN), subscriptions.map { it.name })
        assertSumShown(subscriptions.map { it.leftBytes }, subscriptions.sumOf { it.leftBytes })
        assertSumShown(subscriptions.map { it.usedBytes }, subscriptions.sumOf { it.usedBytes })
        assertEquals(RvData.nodes.size, subscriptions.sumOf { it.nodes })
        subscriptions.forEach { sub -> assertEquals(sub.nodes, RvData.nodes.count { it.subscription == sub.name }) }
        assertTrue(subscriptions.sumOf { it.usedBytes } >= RvData.today.total)
    }

    @Test
    fun splitPartsSumToTodayAndTheirSharesTo100() {
        val split = RvData.split
        val shares = listOf(format.percent(split.proxyShare.toDouble()), format.percent(1.0 - split.proxyShare)).map { it.removeSuffix("%").toInt() }
        assertEquals(100, shares.sum())
    }

    @Test
    fun theLastMinutesOutboundsAreTheRoutesAndAddUpTo100() {
        val shares = RvData.minuteShares
        assertEquals(100, shares.sumOf { it.percent })
        // The main node first, then rule groups that also sent traffic today, each through the node it used today.
        assertEquals(RvData.CURRENT_ID, shares.first().nodeId)
        assertNull(shares.first().group)
        val routes = RvData.ruleOutbounds.associate { it.group to it.nodeId }
        for (share in shares.drop(1)) assertEquals(routes.getValue(share.group!!), share.nodeId)
    }

    @Test
    fun theOutboundCardsPartsAreTodayAndTheirSharesAddUpTo100() {
        val parts = rvOutboundParts(RvRouteMode.Rule, RvData.CURRENT_ID)
        // The main node, two rule groups and the rest folded: four at most, as the owner set the card's rows.
        assertEquals(4, parts.size)
        assertEquals(RvData.today.total, parts.sumOf { it.bytes })
        assertSumShown(parts.map { it.bytes }, RvData.today.total)
        assertEquals(100, rvPercents(parts.map { it.bytes }).sum())
        assertEquals(listOf(100, 0), rvPercents(listOf(5L, 0L)))
        assertEquals(listOf(0, 0), rvPercents(listOf(0L, 0L)))
    }

    @Test
    fun theCurrentNodeIsTheSameEverywhere() {
        val current = RvData.current
        assertEquals(RvPlace.HK, current.place)
        assertEquals(1, RvData.nodes.count { it.id == RvData.CURRENT_ID })
        val activity = rvSampleActivity()
        assertEquals(current.id, activity.node?.id)
        assertEquals(current.name, activity.node?.name)
        assertEquals(current.latencyMs, activity.node?.latencyMs)
        assertEquals(RvData.AUTO_GROUP, activity.outbound)
        assertEquals(session.startEpochMs, (activity.status as Status.Running).sinceEpochMs)
        assertEquals(current.id, sampleNodesPage().currentId)
        assertEquals(current.id, rvStatsSample().selectedId)
        assertTrue(rvStatsSample().nodes.single { it.id == current.id } === current)
    }

    @Test
    fun theFastestCardsAreTheListsNodesById() {
        val list = RvData.nodes.associateBy { it.id }
        val grid = sampleNodesPage().top(steadiest = false)
        val card = RvData.nodes.fastest(3)
        assertEquals(grid.take(card.size).map { it.id }, card.map { it.id })
        for (node in grid) {
            val listed = list.getValue(node.id)
            assertEquals(listed.latencyMs, node.latencyMs)
            assertEquals(listed.changeMs, node.changeMs)
            assertEquals(listed.name, node.name)
            assertTrue(node.latencyMs != null)
        }
        val tested = RvData.nodes.mapNotNull { it.latencyMs }.sorted()
        assertEquals(tested.take(grid.size), grid.map { it.latencyMs })
    }

    @Test
    fun namesFollowOneScheme() {
        assertEquals(48, RvData.nodes.size)
        assertEquals(12, RvData.nodes.map { it.place }.toSet().size)
        assertEquals("%1\$d 個國家或地區", zhString("strings_rv_nodes.xml", "rv_nodes_countries"))
        assertEquals("臺灣", zhString("strings_rv_nodes.xml", "rv_nodes_place_tw"))
        for (node in RvData.nodes) {
            val number = node.id.substringAfter('-')
            assertTrue(node.id, Regex("[a-z]{2}-\\d{2}").matches(node.id))
            assertEquals(node.place.name.lowercase(), node.id.substringBefore('-'))
            assertTrue(node.name, Regex("[A-Z][A-Za-z ]+ $number( [A-Z]+)?").matches(node.name))
            assertEquals("${node.place.name} $number", node.short)
            assertEquals("${node.place.name.lowercase()}${number.toInt()}.example.net", node.host)
        }
    }

    @Test
    fun latencyIsTheLastOfEightTestsEndingAt1000() {
        val times = RvData.testTimes
        assertEquals(RV_TESTS, times.size)
        assertEquals((0 until RV_TESTS).map { "%02d:%02d".format(6 + (it + 1) / 2, if (it % 2 == 0) 30 else 0) }, times.map(::utc))
        for (node in RvData.nodes) {
            assertEquals(RV_TESTS, node.history.size)
            assertEquals(node.history.last(), node.latencyMs)
            val before = node.history[RV_TESTS - 2]
            assertEquals(if (before != null && node.latencyMs != null) node.latencyMs!! - before else null, node.changeMs)
        }
    }

    @Test
    fun everyPeakIsAtLeastItsCurrentValue() {
        val activity = rvSampleActivity()
        assertEquals(session.minuteDown.last().toLong(), activity.downRate)
        assertEquals(session.minuteUp.last().toLong(), activity.upRate)
        assertTrue(session.peakDown >= session.minuteDown.max())
        assertTrue(session.peakUp >= session.minuteUp.max())
    }

    @Test
    fun theSessionIsPartOfToday() {
        val day = RvData.today
        // Nothing before 08:00, the hour the session started in.
        assertTrue(session.downByHour.take(8).all { it == 0L } && session.upByHour.take(8).all { it == 0L })
        assertTrue(session.downTotal <= day.downTotal && session.upTotal <= day.upTotal)
        assertTrue(session.minuteDown.sum() <= session.downByHour.last())
        assertTrue(session.minuteUp.sum() <= session.upByHour.last())
        assertEquals(60, session.minuteDown.size)
    }

    @Test
    fun uploadHasItsOwnShape() {
        assertTrue(correlation(session.minuteDown, session.minuteUp) < 0.9)
        assertTrue(correlation(RvData.today.down.map(Long::toFloat), RvData.today.up.map(Long::toFloat)) < 0.9)
    }

    @Test
    fun theFormatterRoundsOnceToThreeDigits() {
        assertEquals("1.84 GB", format.bytes(1_840_000_000).toString())
        assertEquals("233 MB", format.bytes(233_082_859).toString())
        assertEquals("1.00 GB", format.bytes(999_600_000).toString())
        assertEquals("512 B", format.bytes(512).toString())
        assertEquals("301 kB/s", format.rate(300_653.6).toString())
        assertEquals("61.7", format.bytesIn(61_700_000_000, format.bytes(200_000_000_000)))
        assertEquals("3/15", format.expiry(1_805_112_000_000, RvData.nowEpochMs))
        assertEquals("3/15", RvFormat(Locale.US).expiry(1_805_112_000_000, RvData.nowEpochMs))
    }

    @Test
    fun theMedianIsOneRuleOnEveryPage() {
        assertEquals(15, rvMedian(listOf(20, 10)))
        assertEquals(20, rvMedian(listOf(30, 10, 20)))
        assertEquals(null, rvMedian(emptyList()))
        // Node detail's figure, and 統計's through the same helper: an even count averages the two middle tests.
        val node = RvData.nodes.first { it.history.count { ms -> ms != null } % 2 == 0 }
        val answered = node.history.filterNotNull().sorted()
        assertEquals((answered[answered.size / 2 - 1] + answered[answered.size / 2]) / 2, node.medianMs)
    }

    @Test
    fun edgeInputsStayWritable() {
        assertEquals("0 B/s", format.rate(Double.NaN).toString())
        assertEquals("0 B/s", format.rate(Double.POSITIVE_INFINITY).toString())
        assertEquals("-20.0 GB", format.bytes(-20_000_000_000).toString())
        val justStarted = RvSession(RV_NOW, RV_NOW, listOf(0L), listOf(0L), 0f, 0f, emptyList(), emptyList())
        assertEquals(0.0, justStarted.averageDown, 0.0)
        assertEquals(0L, RvSubscription("s", "s", 0, RV_NOW, 120, 100, null).leftBytes)
    }

    private fun assertSumShown(parts: List<Long>, total: Long) {
        val sum = parts.sumOf { shown(format.bytes(it).toString()).let { range -> (range.start + range.endInclusive) / 2 } }
        assertEquals("${parts.map { format.bytes(it) }}", format.bytes(total).toString(), format.bytes(sum.roundToLong()).toString())
    }

    /** The bytes a shown amount such as `1.84 GB` stands for: its value ± half its last digit. */
    private fun shown(text: String): ClosedFloatingPointRange<Double> {
        val (number, unit) = text.split(' ')
        val factor = 1_000.0.pow(listOf("B", "kB", "MB", "GB", "TB").indexOf(unit))
        val decimals = number.substringAfter('.', "").length
        val half = 0.5 * 10.0.pow(-decimals) * factor
        val value = number.replace(",", "").toDouble() * factor
        return value - half..value + half
    }

    private fun correlation(a: List<Float>, b: List<Float>): Double {
        val ma = a.average()
        val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val va = a.sumOf { (it - ma) * (it - ma) }
        val vb = b.sumOf { (it - mb) * (it - mb) }
        return cov / sqrt(va * vb)
    }

    private fun utc(epochMs: Long) = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).let { "%02d:%02d".format(it.hour, it.minute) }

    /** A zh-TW string (or a plural's `other`) as the resources hold it; unit tests run in the app module's directory. */
    private fun zhString(file: String, name: String): String {
        val xml = File("src/debug/res/values-zh-rTW/$file").readText()
        val string = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)
        val plural = Regex("<plurals name=\"$name\">.*?<item quantity=\"other\">([^<]*)</item>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return (string ?: plural)!!.groupValues[1]
    }
}
