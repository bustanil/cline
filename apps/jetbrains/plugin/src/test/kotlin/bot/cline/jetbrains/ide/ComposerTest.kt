package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.Attachment
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.TransferHandler

class ComposerTest : BasePlatformTestCase() {
    private val sent = mutableListOf<Triple<String, List<String>, List<String>>>()
    private var chosen: List<File> = emptyList()
    private val dir = FileUtil.createTempDirectory("composer", null)

    private fun composer(): Composer = Composer(project, store = { null }, onSubmit = { t, i, f -> sent += Triple(t, i, f) }, chooseFiles = { chosen })
        .also { it.update(true) }

    private fun png(width: Int = 4, height: Int = 4): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun file(name: String, bytes: ByteArray = "x".toByteArray()) = File(dir, name).also { it.writeBytes(bytes) }

    private fun key(c: Composer, code: Int, shift: Boolean = false): KeyEvent {
        val event = KeyEvent(c.input, KeyEvent.KEY_PRESSED, 0, if (shift) KeyEvent.SHIFT_DOWN_MASK else 0, code, KeyEvent.CHAR_UNDEFINED)
        c.input.keyListeners.forEach { it.keyPressed(event) }
        return event
    }

    fun `test send needs text or an attachment`() {
        val c = composer()
        assertFalse(c.sendEnabled)
        c.input.text = "hello"
        assertTrue(c.sendEnabled)
        c.input.text = ""
        c.addFiles(listOf(file("a.txt")))
        assertTrue(c.sendEnabled)
    }

    fun `test the plus button attaches what the chooser returns and the strip shows it`() {
        chosen = listOf(file("shot.png", png()), file("spec.md"))
        val c = composer()
        c.findPlus().doClick()
        assertEquals(listOf("shot.png", "spec.md"), c.strip().chipNames)
        assertEquals(1, c.attachments.images.size)
        assertEquals(1, c.attachments.files.size)
        assertTrue(c.attachments.images[0].startsWith("data:image/png;base64,"))
    }

    fun `test sending passes the text images and files then clears them`() {
        val c = composer()
        c.addFiles(listOf(file("shot.png", png()), file("spec.md")))
        c.input.text = "  look at these  "
        c.submitForTest()
        val (text, images, files) = sent.single()
        assertEquals("look at these", text)
        assertEquals(1, images.size)
        assertEquals(listOf(File(dir, "spec.md").absolutePath), files)
        assertEquals("", c.input.text)
        assertTrue(c.attachments.isEmpty)
        assertTrue(c.strip().chipNames.isEmpty())
        assertFalse(c.sendEnabled)
    }

    fun `test files that are refused are named with the reason`() {
        val c = composer()
        c.addFiles(listOf(file("run.exe"), file("ok.txt")))
        assertEquals(listOf("ok.txt"), c.strip().chipNames)
        assertTrue(c.statusText, c.statusText.contains("run.exe") && c.statusText.contains("not accepted"))
        c.attachments.imagesAllowed = false
        c.addFiles(listOf(file("i.png", png())))
        assertTrue(c.statusText, c.statusText.contains("does not accept images"))
    }

    fun `test an attachment can be removed with its button`() {
        val c = composer()
        c.addFiles(listOf(file("a.txt"), file("b.txt")))
        c.strip().removeAt(0)
        assertEquals(listOf("b.txt"), c.strip().chipNames)
    }

    fun `test pasting an image and dropping files attach them and text still pastes as text`() {
        val c = composer()
        val image = BufferedImage(6, 3, BufferedImage.TYPE_INT_ARGB)
        val imageOnly = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.imageFlavor
            override fun getTransferData(f: DataFlavor): Any = image
        }
        assertTrue(c.input.transferHandler.importData(TransferHandler.TransferSupport(c.input, imageOnly)))
        assertEquals(listOf("pasted-image.png"), c.strip().chipNames)
        val files = listOf(file("dropped.csv"))
        val dropped = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.javaFileListFlavor
            override fun getTransferData(f: DataFlavor): Any = files
        }
        assertTrue(c.input.transferHandler.importData(TransferHandler.TransferSupport(c.input, dropped)))
        assertEquals(listOf("pasted-image.png", "dropped.csv"), c.strip().chipNames)
        val text = java.awt.datatransfer.StringSelection("plain words")
        assertTrue(c.input.transferHandler.importData(TransferHandler.TransferSupport(c.input, text)))
        assertEquals("plain words", c.input.text)
    }

    fun `test Enter sends and Shift Enter does not and the key hook goes first`() {
        val c = composer()
        c.input.text = "one"
        key(c, KeyEvent.VK_ENTER, shift = true)
        assertTrue(sent.isEmpty())
        val enter = key(c, KeyEvent.VK_ENTER)
        assertTrue(enter.isConsumed)
        assertEquals("one", sent.single().first)
        c.input.text = "two"
        c.keyHandler = { true }
        key(c, KeyEvent.VK_ENTER)
        assertEquals(1, sent.size)
    }

    fun `test nothing is sent while disconnected`() {
        val c = Composer(project, store = { null }, onSubmit = { t, i, f -> sent += Triple(t, i, f) })
        c.input.text = "hello"
        c.submitForTest()
        assertTrue(sent.isEmpty())
    }

    fun `test snapshot of the composer`() {
        val store = bot.cline.jetbrains.chat.ChatStore(object : bot.cline.jetbrains.bridge.CoreApi {
            override fun call(service: String, method: String, messageJson: String) = java.util.concurrent.CompletableFuture.completedFuture(
                when (method) {
                    "getLatestState" -> com.google.gson.Gson().toJson(mapOf("stateJson" to """{"mode":"act","epoch":1,"stateVersion":1,"clineMessages":[],"apiConfiguration":{"actModeApiProvider":"deepseek","actModeApiModelId":"deepseek-v4-1-flash"}}"""))
                    "readProviderConfig" -> """{"actSelection":{"modelId":"deepseek-v4-1-flash","modelInfo":{"name":"DeepSeek-V4.1-Flash","contextWindow":1000000,"supportsImages":true,"supportsReasoning":true}}}"""
                    else -> "{}"
                },
            )
            override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = bot.cline.jetbrains.bridge.Subscription {}
        }).also { it.start().get() }
        ClineSettings.getInstance().rememberEffort("deepseek", "high")
        try {
            val empty = Composer(project, store = { store }, onSubmit = { _, _, _ -> }).also { it.update(true) }
            repeat(10) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }
            empty.update(true)
            Snapshots.save(empty, "composer-empty", 760)
            val full = Composer(project, store = { store }, onSubmit = { _, _, _ -> }).also { it.update(true) }
            repeat(10) { UIUtil.dispatchAllInvocationEvents(); Thread.sleep(20) }
            full.update(true)
            full.input.text = "Explain what this screenshot shows and check it against the spec"
            full.addFiles(listOf(file("screenshot-2026-10-22.png", png(40, 30)), file("payments-spec.pdf"), file("run.exe")))
            Snapshots.save(full, "composer-attached", 760)
        } finally {
            ClineSettings.getInstance().state.efforts.clear()
        }
    }

    fun `test snapshot of a sent prompt with attachments`() {
        val pic = png(160, 100)
        val url = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pic)
        val raw = com.google.gson.JsonObject().apply {
            addProperty("ts", 1); addProperty("type", "say"); addProperty("say", "task"); addProperty("text", "Explain what this screenshot shows and check it against the spec")
            add("images", com.google.gson.JsonArray().apply { add(url) })
            add("files", com.google.gson.JsonArray().apply { add("/work/docs/payments-spec.pdf") })
        }
        val panel = javax.swing.JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS); isOpaque = true; background = ChatColors.page }
        panel.add(HtmlRow(bot.cline.jetbrains.chat.TranscriptItem.Single(bot.cline.jetbrains.chat.ChatMessage(raw)), RowContext(bot.cline.jetbrains.chat.FileLinks(object : bot.cline.jetbrains.bridge.CoreApi {
            override fun call(service: String, method: String, messageJson: String) = java.util.concurrent.CompletableFuture.completedFuture("{}")
            override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = bot.cline.jetbrains.bridge.Subscription {}
        }))).component)
        Snapshots.save(panel, "prompt-attachments", 760)
    }

    private fun Composer.findPlus(): javax.swing.JButton {
        fun walk(c: java.awt.Component): List<java.awt.Component> = listOf(c) + ((c as? java.awt.Container)?.components?.flatMap(::walk) ?: emptyList())
        return walk(this).filterIsInstance<PillButton>().first { it.text == "+" }
    }
}
