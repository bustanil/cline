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
        var core: CoreConnection? = null
        val workspace = File.createTempFile("cline-ws", "").also { it.delete(); it.mkdirs() }
        File(workspace, "a.txt").writeText("one\ntwo\nthree\nfour\n")
        val userMessages = java.util.concurrent.CopyOnWriteArrayList<UserMessage>()
        val opened = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Int?, Int?>>()
        val server = CoreConnectionServer(
            token,
            object : IdeBridge {
                override fun workspacePaths() = listOf(workspace.path)
                override fun showMessage(message: UserMessage): String? { userMessages += message; return null }
                override fun showTextDocument(path: String, startLine: Int?, endLine: Int?) { opened += Triple(path, startLine, endLine) }
            },
        ) { core = it; connected.countDown() }.start()
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
            val state = core!!.call("cline.StateService", "getLatestState").get(60, TimeUnit.SECONDS)
            println("STATE: " + state.length + " chars; starts " + state.take(200))
            assertTrue(state.contains("stateJson"), "state response: " + state.take(300))

            // A file path written in chat resolves against the workspace and opens at its lines.
            val links = bot.cline.jetbrains.chat.FileLinks(core!!)
            val link = links.resolve("a.txt:2-3").get(30, TimeUnit.SECONDS)
            assertTrue(link != null && link.path.endsWith("/a.txt") && link.startLine == 2 && link.endLine == 3, "resolve gave $link")
            assertTrue(links.resolve("nope-not-here.txt").get(30, TimeUnit.SECONDS) == null)
            links.open(link!!).get(30, TimeUnit.SECONDS)
            assertTrue(opened.any { it.first.endsWith("/a.txt") && it.second == 2 && it.third == 3 }, "bridge got $opened")
            println("LINK: " + link + " opened=" + opened)

            // With no checkpoint yet, the changed-files call answers with an empty list rather than an error.
            val none = bot.cline.jetbrains.chat.ChatStore(core!!).changedFiles().get(30, TimeUnit.SECONDS)
            assertTrue(none.isEmpty(), "changed files before any task: $none")

            // The @ mention search runs in the workspace and finds the file by a part of its name.
            val mention = bot.cline.jetbrains.chat.ChatStore(core!!).searchMentions("a.tx").get(60, TimeUnit.SECONDS)
            println("MENTION: " + mention)
            assertTrue(mention.matches.any { it.path.endsWith("a.txt") }, "mention search: $mention")

            // Provider, model, key, mode and history go through the same stream.
            val settings = bot.cline.jetbrains.chat.ChatStore(core!!)
            settings.start().get(60, TimeUnit.SECONDS)
            val providers = settings.models.providers().get(30, TimeUnit.SECONDS)
            assertTrue(providers.any { it.id == "anthropic" }, "providers: ${providers.map { it.id }}")
            settings.models.select("anthropic", "act", "claude-sonnet-5").get(30, TimeUnit.SECONDS)
            val deadline0 = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline0 && bot.cline.jetbrains.chat.ModelSettings.current(settings.snapshot.extensionState, "act").providerId != "anthropic") Thread.sleep(100)
            println("MODEL: " + bot.cline.jetbrains.chat.ModelSettings.current(settings.snapshot.extensionState, "act", providers))
            assertTrue(bot.cline.jetbrains.chat.ModelSettings.current(settings.snapshot.extensionState, "act").providerId == "anthropic")
            settings.models.saveApiKey("anthropic", "sk-test-123").get(30, TimeUnit.SECONDS)
            assertTrue(settings.models.apiKeyLength("anthropic").get(30, TimeUnit.SECONDS) == 11)
            val list = settings.models.models("anthropic").get(60, TimeUnit.SECONDS)
            assertTrue(list.ok && list.models.any { it.id.startsWith("claude") }, "models: $list")
            settings.setMode("plan").get(30, TimeUnit.SECONDS)
            val deadline1 = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline1 && settings.mode != "plan") Thread.sleep(100)
            assertTrue(settings.mode == "plan", "mode is ${settings.mode}")
            assertTrue(settings.history.list().get(30, TimeUnit.SECONDS).items.isEmpty())
            assertTrue(runCatching { settings.history.open("no-such-task").get(30, TimeUnit.SECONDS) }.isFailure)
            // Back to a provider with no key, so the task below fails locally and sends nothing to a remote API.
            settings.models.select("openrouter", "act", "anthropic/claude-sonnet-5.5").get(30, TimeUnit.SECONDS)
            settings.models.saveApiKey("anthropic", "").get(30, TimeUnit.SECONDS)
            settings.stop()
            // Put Plan/Act back so the task below runs the way the earlier checks assume.
            settings.setMode("act").get(30, TimeUnit.SECONDS)

            // A task with no provider credential fails, and the failure must reach the transcript.
            val store = bot.cline.jetbrains.chat.ChatStore(core!!)
            store.start().get(60, TimeUnit.SECONDS)
            store.newTask("say hello").get(60, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && store.buttons().primaryText != "Retry") Thread.sleep(250)
            val kinds = store.snapshot.messages.map { (it.raw.get("type")?.asString ?: "?") + "/" + (it.raw.get("say") ?: it.raw.get("ask")) }
            println("TRANSCRIPT: " + kinds + " turn=" + store.snapshot.turnState?.phase + " userMessages=" + userMessages.map { it.type + ":" + it.text.take(80) })
            assertTrue(store.snapshot.messages.isNotEmpty(), "no messages arrived after newTask")
            println("BUTTONS: " + store.buttons())
            assertTrue(store.buttons().primaryText == "Retry", "expected Retry, got " + store.buttons())
            // A task with an image and a file: the core keeps both in the task message.
            store.clearTask().get(30, TimeUnit.SECONDS)
            val attach = bot.cline.jetbrains.chat.Attachments()
            val pic = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            attach.addImageBytes(pic, "image/png", "p.png")
            attach.addFile(File(workspace, "a.txt"))
            store.newTask("describe these", attach.images, attach.files).get(60, TimeUnit.SECONDS)
            val deadlineA = System.currentTimeMillis() + 60_000
            var taskMessage: bot.cline.jetbrains.chat.ChatMessage? = null
            while (System.currentTimeMillis() < deadlineA && taskMessage == null) {
                taskMessage = store.snapshot.messages.firstOrNull { it.raw.get("say")?.asString == "task" && it.raw.get("text")?.asString == "describe these" }
                if (taskMessage == null) Thread.sleep(250)
            }
            println("ATTACHED: images=" + taskMessage?.raw?.getAsJsonArray("images")?.size() + " files=" + taskMessage?.raw?.getAsJsonArray("files")?.size() + " file0=" + taskMessage?.raw?.getAsJsonArray("files")?.firstOrNull())
            assertTrue(taskMessage != null && (taskMessage!!.raw.getAsJsonArray("images")?.size() ?: 0) == 1, "task message: " + taskMessage?.raw)
            assertTrue((taskMessage!!.raw.getAsJsonArray("files")?.size() ?: 0) == 1)
            store.stop()
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
            server.stop()
        }
    }
}
