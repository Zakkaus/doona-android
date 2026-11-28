package dev.doona.android.feature.policies

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext

/**
 * A group's page as the app draws it: the RV sample as honk serves it, GroupViewModel, the mapper, then the page of the
 * s1125d sample (goldens `app-rv-group-*`). It is the 串流 selector over the sample's two subscriptions, with the switch
 * off as honk's networks agree, and on once UDP uses another node; before honk has answered it is the skeleton under the
 * group's name. The sample instant is the page's now. A Score group over the sample's Hong Kong nodes ([scored]) has the
 * approved Score cards' layout; honk reports no Score evidence, so they show none. A group with no member says so.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvGroupShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun streamingLight() = shot("group", dark = false, whole = true) { Page(HonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE)
    fun streamingDark() = shot("group", dark = true, whole = true) { Page(HonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun streamingFs200() = shot("group", dark = false, fontScale = RV_FS200, whole = true) { Page(HonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE_EN)
    fun streamingEnLight() = shot("group-en", dark = false, whole = true) { Page(HonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE_APART)
    fun apartLight() = shot("group-apart", dark = false, whole = true) { Page(::apart) }

    @Test
    @Config(qualifiers = WHOLE_APART)
    fun apartDark() = shot("group-apart", dark = true, whole = true) { Page(::apart) }

    /** A selector over groups and nodes as a user writes it in honk by hand ([honkGroups]): its groups stand as rows. */
    @Test
    fun honkGroupsLight() = shot("group-honk", dark = false) { Page(::honkGroups, HONK_EXIT) }

    /** The first read failed: the group's name and the banner with 重試, no skeleton left standing. */
    @Test
    fun readFailedLight() = shot("group-read-failed", dark = false) { Page(::unreadable) }

    @Test
    fun loadingLight() = shot("group-loading", dark = false) { Page(HonkFixtures::rvSample, io = Unread) }

    @Test
    fun loadingDark() = shot("group-loading", dark = true) { Page(HonkFixtures::rvSample, io = Unread) }

    @Test
    @Config(qualifiers = WHOLE_SCORE)
    fun scoreLight() = shot("group-score", dark = false, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE)
    fun scoreDark() = shot("group-score", dark = true, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE_FS200)
    fun scoreFs200() = shot("group-score", dark = false, fontScale = RV_FS200, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE_JOINT)
    fun scoreJointLight() = shot("group-score-joint", dark = false, whole = true) { Page({ scored(apart = false) }, SCORE) }

    /** 空 in [honkGroups], a selector with no member. */
    @Test
    fun emptyLight() = shot("group-empty", dark = false) { Page(::honkGroups, "g-空") }

    @Test
    fun emptyDark() = shot("group-empty", dark = true) { Page(::honkGroups, "g-空") }

    /** Score chooses the nodes, so a tap on a node opens its detail and chooses nothing. */
    @Test
    fun aTapInAScoreGroupOpensTheNodeDetail() {
        val details = mutableListOf<String>()
        val honk = scored(apart = true)
        compose.rvContent(dark = false) {
            val vm = remember { GroupViewModel(honk, SCORE, clock = { SAMPLE_NOW }, io = Dispatchers.Unconfined) }
            GroupRoute(vm, SCORE, onBack = {}, onNodeDetail = { details += it })
        }
        val node = members("HK").last().jsonObject
        compose.onAllNodes(hasText(node.text("name")))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(node.text("id")), details)
        assertEquals(emptyList<FakeHonk.Call>(), honk.calls.filter { it.method == "PUT" })
    }

    /** A failed choice leaves the library's banner at the top: what failed, honk's reason and 重試 (plan 8). */
    @Test
    fun aFailedChoiceShowsItsBannerAndRetries() {
        val honk = HonkFixtures.rvSample().apply { on("PUT", "/api/v1/groups/$STREAMING/selection", 409, ERROR) }
        val node = members("Streaming").last().jsonObject.text("name")
        compose.rvContent(dark = false) { Page({ honk }) }
        assertEquals(0, shown(text(R.string.rv_failed_switch_node)))
        // The row's own click: a tap at its centre can land under the floating 測試群組.
        compose.onAllNodes(hasText(node))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, shown(text(R.string.rv_failed_switch_node)))
        assertEquals(1, shown(text(R.string.error_busy)))
        honk.on("PUT", "/api/v1/groups/$STREAMING/selection", 200, "{}")
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(2, honk.calls.count { it.method == "PUT" })
        assertEquals(0, shown(text(R.string.rv_failed_switch_node)))
    }

    /** 測試群組 probes this group. */
    @Test
    fun theButtonTestsTheGroup() {
        val honk = HonkFixtures.rvSample().apply {
            on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued"))
            on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
        }
        compose.rvContent(dark = false) { Page({ honk }) }
        compose.onNode(hasText(text(R.string.rv_policy_test_group)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val probe = Json.parseToJsonElement(honk.calls.first { it.path == "/api/v1/probes" }.body!!).jsonObject
        assertEquals(STREAMING, probe.getValue("target").jsonObject.text("group_id"))
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, whole: Boolean = false, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        if (whole) compose.assertWhole()
        compose.drawFully()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    /** The route on a view model with the sample's clock, reading honk on [io] (the test's thread). */
    @Composable
    private fun Page(honk: () -> FakeHonk, groupId: String = STREAMING, io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val vm = remember { GroupViewModel(honk(), groupId, clock = { SAMPLE_NOW }, io = io) }
        GroupRoute(vm, groupId, onBack = {})
    }

    /** The sample with 串流's UDP on its last member, so the switch starts on. */
    private fun apart(): FakeHonk = HonkFixtures.rvSample().apply {
        val streaming = fixture("Streaming")
        val runtime = streaming.getValue("runtime").jsonObject
        val selection = runtime.getValue("selection").jsonObject
        val last = members("Streaming").last().jsonObject.text("id")
        val udp = selection(last, last, "override")
        val edited = streaming + ("runtime" to JsonObject(runtime + ("selection" to JsonObject(selection + ("udp" to udp)))))
        on("GET", "/api/v1/groups/$STREAMING", 200, JsonObject(edited).toString())
    }

    /** The sample whose nodes cannot be read. */
    private fun unreadable(): FakeHonk = HonkFixtures.rvSample().apply { on("GET", "/api/v1/nodes", 503, ERROR) }

    /**
     * The sample with groups as a user may write them in honk by hand: 出口, a selector over two groups, two subscription
     * nodes and a node added by hand (no provider), that uses 香港; 香港, a url-test; 日本, a fallback; and 空, a selector
     * with no member.
     */
    private fun honkGroups(): FakeHonk = HonkFixtures.rvSample().apply {
        val hk = fixture("HK")
        val hkChoice = hk.getValue("runtime").jsonObject.getValue("selection").jsonObject.getValue("tcp").jsonObject
        val proxyNodes = members("Proxy").take(2)
        val exitMembers = listOf(member("g-香港", "香港", "group"), member("g-日本", "日本", "group")) + proxyNodes +
            member(HONK_BY_HAND, "Home Relay", "node")
        val jpFirst = members("JP").first().jsonObject.text("id")
        on("GET", "/api/v1/groups/$HONK_EXIT", 200, group(HONK_EXIT, "出口", "selector", exitMembers, selection("g-香港", hkChoice.text("member_id"), "runtime")))
        on("GET", "/api/v1/groups/g-香港", 200, group("g-香港", "香港", "urltest", members("HK"), hkChoice))
        on("GET", "/api/v1/groups/g-日本", 200, group("g-日本", "日本", "fallback", members("JP"), selection(jpFirst, jpFirst, "health")))
        on("GET", "/api/v1/groups/g-空", 200, group("g-空", "空", "selector", emptyList(), JsonNull))
        val nodes = Json.parseToJsonElement(HonkFixtures.body("nodes/rv-sample")).jsonObject
        val relay = Json.parseToJsonElement(
            """{"id":"$HONK_BY_HAND","name":"Home Relay","protocol":null,"subscription_tag":null,"provider_id":null,"group_ids":["$HONK_EXIT"],""" +
                """"health":[{"transport":"tcp","state":"healthy","latency_ms":48,"observed_at":"2026-11-22T10:00:00Z","error":null}]}""",
        )
        on("GET", "/api/v1/nodes", 200, JsonObject(nodes + ("nodes" to JsonArray(nodes.getValue("nodes").jsonArray + relay))).toString())
    }

    /** The sample with a Score group over its Hong Kong nodes: TCP on the fastest; UDP, [apart], on the second, else on TCP's. */
    private fun scored(apart: Boolean): FakeHonk = HonkFixtures.rvSample().apply {
        val latency = Json.parseToJsonElement(HonkFixtures.body("nodes/rv-sample")).jsonObject.getValue("nodes").jsonArray.associate { node ->
            node.jsonObject.text("id") to node.jsonObject.getValue("health").jsonArray.firstOrNull()?.jsonObject?.getValue("latency_ms")?.jsonPrimitive?.doubleOrNull
        }
        val fastest = members("HK").map { it.jsonObject.text("id") }.sortedBy { latency[it] ?: Double.MAX_VALUE }
        val udp = if (apart) fastest[1] else fastest[0]
        on("GET", "/api/v1/groups/$SCORE", 200, group(SCORE, "Hong Kong", "score", members("HK"), selection(fastest[0], fastest[0], "runtime"), selection(udp, udp, "runtime")))
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(HonkFixtures.body("groups/rv-sample-g-$name")).jsonObject

    private fun members(name: String): List<JsonElement> = fixture(name).getValue("members").jsonArray

    private fun member(id: String, name: String, kind: String): JsonElement = buildJsonObject {
        put("id", id)
        put("name", name)
        put("kind", kind)
    }

    private fun selection(member: String, leaf: String, source: String): JsonElement = buildJsonObject {
        put("member_id", member)
        put("resolved_leaf_node_id", leaf)
        put("source", source)
    }

    private fun group(id: String, name: String, kind: String, members: List<JsonElement>, tcp: JsonElement, udp: JsonElement = tcp): String =
        JsonObject(
            fixture("Proxy") + mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "policy" to buildJsonObject {
                    put("kind", kind)
                    put("native", kind)
                },
                "members" to JsonArray(members),
                "runtime" to buildJsonObject {
                    put("selection", JsonObject(mapOf("tcp" to tcp, "udp" to udp)))
                    put("health", JsonArray(emptyList()))
                },
            ),
        ).toString()

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** A dispatcher that never runs what it is given: the first read never ends, as before honk answers. */
    private object Unread : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val STREAMING = "g-Streaming"
        const val SCORE = "g-Hong Kong"
        const val ERROR = """{"error":{"code":"conflict","message":"The request conflicts with the current state."},"request_id":"request-1"}"""

        /** Windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE = "+h3289dp"
        const val WHOLE_FS200 = "+h6440dp"
        const val WHOLE_EN = "en-w412dp-h3248dp-notnight-xhdpi"
        const val WHOLE_APART = "+h3495dp"
        const val WHOLE_SCORE = "+h1993dp"
        const val WHOLE_SCORE_FS200 = "+h3384dp"
        const val WHOLE_SCORE_JOINT = "+h1720dp"
    }
}
