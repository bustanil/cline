package bot.cline.jetbrains.bridge

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Starts the real `cline-core` and waits for it to connect and receive `ready`.
 * Runs only when CLINE_CORE_JS points at dist-standalone/cline-core.js.
 */
class RealCoreSmokeTest {
    @Test
    fun `real core connects and gets ready`() {
        val coreJs = System.getenv("CLINE_CORE_JS")
        if (coreJs == null) {
            println("SKIPPED: CLINE_CORE_JS is not set")
            return
        }
        val token = UUID.randomUUID().toString()
        val connected = CountDownLatch(1)
        val server = CoreConnectionServer(token) { connected.countDown() }.start()
        val log = File.createTempFile("cline-core", ".log")
        val process = ProcessBuilder(System.getenv("CLINE_NODE") ?: "node", coreJs)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply {
                environment()["CLINE_CORE_CONNECTION_TOKEN"] = token
                environment()["CLINE_CORE_INSTANCE_ID"] = "smoke-" + UUID.randomUUID()
                environment()["HOST_BRIDGE_ADDRESS"] = "127.0.0.1:${server.port}"
                // Keep the smoke test out of the user's real ~/.cline data.
                environment()["CLINE_DIR"] = File.createTempFile("cline-data", "").also { it.delete(); it.mkdirs() }.path
            }
            .start()
        try {
            val ok = connected.await(90, TimeUnit.SECONDS)
            assertTrue(ok, "core did not connect; log:\n" + log.readText().takeLast(3000))
            println("CONNECTED: core said hello and got ready")
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
            server.stop()
        }
    }
}
