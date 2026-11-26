package dev.doona.android.feature.config

import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.engine.fake.RvSampleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The approved panels' sample data against what the engine path computes from [RvSampleConfig]: the line counts the
 * overview lists and the hunks the diff pages draw, so the real routes render what the owner approved.
 */
class ConfigSampleDataTest {
    private fun RvDiffFile?.lines() = this?.hunks?.map { run -> run.map { Triple(it.kind, it.line, it.text) } }

    /**
     * Every file but dns.dae: the approved overview says 17 lines, and the tree's DNS needs 19 to declare the three
     * servers, four request rules and one response rule the same overview counts (open question in p14-STATE).
     */
    @Test
    fun theTreesFilesHaveTheOverviewsLineCounts() {
        val files = RvSampleConfig.files(RvSampleConfig.texts()).filter { it.path != "dns.dae" }
        assertEquals(listOf(36, 12, 8, 21, 9), files.map { it.lines })
        assertEquals(RvConfigData.files.filter { it.path != "dns.dae" }.map { it.path to it.lines }, files.map { it.path to it.lines })
    }

    @Test
    fun theCheckedDraftsRuleFileDiffIsTheApprovedOne() {
        val base = RvSampleConfig.texts().getValue(RvSampleConfig.CN)
        val draft = RvSampleConfig.texts(cnTarget = "media", wiki = true).getValue(RvSampleConfig.CN)
        val approved = RvConfigSourceData.diff.files.single { it.source == RvConfigSourceData.CN }
        assertEquals(approved.lines(), rvLineDiff(RvConfigSourceData.CN, base, draft).lines())
    }

    @Test
    fun restoringAnOlderRevisionDiffsAsTheApprovedRevisionPage() {
        val head = RvSampleConfig.texts().getValue(RvSampleConfig.ENTRY)
        val older = RvSampleConfig.texts(aiKeyword = "US").getValue(RvSampleConfig.ENTRY)
        assertEquals(RvConfigHistoryData.restoreDiff.lines(), rvLineDiff(RvConfigData.ENTRY, head, older).lines())
    }

    @Test
    fun anUnchangedFileHasNoDiff() {
        val text = RvSampleConfig.texts().getValue(RvSampleConfig.DNS)
        assertNull(rvLineDiff("dns.dae", text, text))
    }
}
