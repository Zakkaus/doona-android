package dev.doona.android.data.settings

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** 檢查更新's request, against a local server that answers as GitHub's releases API would. */
class UpdateCheckTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val url get() = "http://127.0.0.1:${server.address.port}/latest"

    @After
    fun tearDown() = server.stop(0)

    private fun answer(code: Int, body: String) {
        server.createContext("/latest") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    /** 關於's 隱私 opens `PRIVACY.md` on GitHub; the file is at the repository's root and names the request 檢查更新 sends. */
    @Test
    fun `the privacy link is the root's PRIVACY_md, which names 檢查更新's request`() {
        assertTrue(UpdateCheck.PRIVACY.endsWith("/blob/HEAD/PRIVACY.md"))
        val notice = File("../../../PRIVACY.md")
        assertTrue("${notice.absolutePath} exists", notice.isFile)
        assertTrue(UpdateCheck.LATEST in notice.readText())
    }

    @Test
    fun `the latest release's tag`() = runBlocking {
        answer(200, """{"tag_name":"v0.2.0","name":"doona 0.2.0","prerelease":false}""")
        assertEquals("v0.2.0", UpdateCheck.latestRelease(url).getOrThrow())
    }

    @Test
    fun `no release published yet is no tag, not a failure`() = runBlocking {
        answer(404, """{"message":"Not Found"}""")
        assertNull(UpdateCheck.latestRelease(url).getOrThrow())
    }

    @Test
    fun `a refused request or an answer that is no release fails`() = runBlocking {
        answer(403, """{"message":"API rate limit exceeded"}""")
        assertTrue(UpdateCheck.latestRelease(url).isFailure)
        server.removeContext("/latest")
        answer(200, "<html>captive portal</html>")
        assertTrue(UpdateCheck.latestRelease(url).isFailure)
        server.removeContext("/latest")
        answer(200, """{"name":"no tag"}""")
        assertTrue(UpdateCheck.latestRelease(url).isFailure)
    }

    @Test
    fun `no server at all fails`() = runBlocking {
        server.stop(0)
        assertTrue(UpdateCheck.latestRelease(url).isFailure)
    }
}
