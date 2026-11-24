package dev.doona.android.engine.fake

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.StaticNode
import dev.doona.android.engine.StaticNodeDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeEngineStaticNodesTest {
    @Test
    fun staticEditorCallsReturnFailuresInsteadOfThrowing() = runTest {
        val engine = FakeEngine()
        val node = StaticNode("n-demo", "Demo", "socks5://192.0.2.1:1080")
        val draft = StaticNodeDraft.Link(node.link)
        val results = listOf(
            engine.previewStaticNodes(listOf(draft)),
            engine.addStaticNodes(listOf(draft)),
            engine.readStaticNode(node.id),
            engine.updateStaticNode(node, draft),
            engine.removeStaticNode(node, confirmed = true),
        )
        for (result in results) {
            assertEquals(ErrorCode.Internal, (result.exceptionOrNull() as? EngineException)?.code)
        }
    }
}
