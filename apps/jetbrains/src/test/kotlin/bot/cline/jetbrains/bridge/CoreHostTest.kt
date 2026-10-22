package bot.cline.jetbrains.bridge

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreHostTest {
    private fun script(body: String) = File.createTempFile("fake-core", ".js").also { it.writeText(body); it.deleteOnExit() }

    @Test
    fun `a core that exits is started again with growing delays`() {
        val host = CoreHost(CoreLaunch("node", script("process.exit(1)"), initialRestartDelayMs = 100, maxRestartDelayMs = 400))
        try {
            host.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (host.starts < 4 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(host.starts >= 4, "starts=${host.starts}")
        } finally {
            host.close()
        }
    }

    @Test
    fun `stop prevents restarts`() {
        val host = CoreHost(CoreLaunch("node", script("setTimeout(() => {}, 60000)"), initialRestartDelayMs = 50))
        host.start()
        Thread.sleep(300)
        host.stop()
        val count = host.starts
        Thread.sleep(500)
        assertEquals(count, host.starts)
        host.close()
    }

    @Test
    fun `real core connects through the host and reconnects after being killed`() {
        val coreJs = System.getenv("CLINE_CORE_JS")
        if (coreJs == null) {
            println("SKIPPED: CLINE_CORE_JS is not set")
            return
        }
        val data = File.createTempFile("cline-data", "").also { it.delete(); it.mkdirs() }
        val host = CoreHost(CoreLaunch("node", File(coreJs), dataDir = data, initialRestartDelayMs = 200))
        val first = CountDownLatch(1)
        val second = CountDownLatch(2)
        val dropped = CountDownLatch(1)
        host.onConnected { first.countDown(); second.countDown() }
        host.onDisconnected { dropped.countDown() }
        try {
            host.start()
            assertTrue(first.await(90, TimeUnit.SECONDS), "core never connected")
            val state = host.connection!!.call("cline.StateService", "getLatestState").get(60, TimeUnit.SECONDS)
            assertTrue(state.contains("stateJson"))
            ProcessHandle.allProcesses()
                .filter { h -> h.info().arguments().map { a -> a.any { it.endsWith("cline-core.js") } }.orElse(false) }
                .forEach { it.destroyForcibly() }
            assertTrue(dropped.await(30, TimeUnit.SECONDS), "disconnect not reported")
            assertTrue(second.await(90, TimeUnit.SECONDS), "core did not reconnect")
            println("RECONNECTED: starts=" + host.starts)
        } finally {
            host.close()
        }
    }
}
