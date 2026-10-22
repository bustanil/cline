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

    private fun composer(): Composer = Composer(project, store = { null }, onSubmit = { t, i, f -> sent += Triple(t, i, f) }, chooseFiles = { chosen }, clipboardImage = { null })
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

    private fun JPopupMenuHelper.item(menu: javax.swing.JPopupMenu, path: String): javax.swing.JMenuItem {
        val top = menu.components.filterIsInstance<javax.swing.JMenu>().first()
        return (top.menuComponents.filterIsInstance<javax.swing.JMenuItem>().first { it.text == path })
    }

    private object JPopupMenuHelper

    fun `test the plus menu has an Add files submenu with the ways to add`() {
        val menu = composer().plusMenu()
        assertEquals(1, menu.componentCount)
        val add = menu.components.single() as javax.swing.JMenu
        assertEquals("Add files", add.text)
        assertEquals(listOf("From computer…", "From this project…", "Image from clipboard"), add.menuComponents.filterIsInstance<javax.swing.JMenuItem>().map { it.text })
    }

    fun `test the project item starts the chooser in the project folder and the computer item does not`() {
        val starts = mutableListOf<File?>()
        val c = Composer(project, store = { null }, onSubmit = { _, _, _ -> }, chooseFiles = { start -> starts += start; emptyList() }, clipboardImage = { null }).also { it.update(true) }
        val menu = c.plusMenu()
        JPopupMenuHelper.item(menu, "From computer…").doClick()
        JPopupMenuHelper.item(menu, "From this project…").doClick()
        assertEquals(null, starts[0])
        assertEquals(project.basePath?.let(::File), starts[1])
    }

    fun `test the clipboard item is enabled only when an image is there and adds it`() {
        val none = Composer(project, store = { null }, onSubmit = { _, _, _ -> }, clipboardImage = { null })
        assertFalse(JPopupMenuHelper.item(none.plusMenu(), "Image from clipboard").isEnabled)
        val image = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
        val some = Composer(project, store = { null }, onSubmit = { _, _, _ -> }, clipboardImage = { image }).also { it.update(true) }
        val item = JPopupMenuHelper.item(some.plusMenu(), "Image from clipboard")
        assertTrue(item.isEnabled)
        item.doClick()
        assertEquals(listOf("pasted-image.png"), some.strip().chipNames)
    }

    fun `test choosing files from the plus menu attaches what the chooser returns and the strip shows it`() {
        chosen = listOf(file("shot.png", png()), file("spec.md"))
        val c = composer()
        JPopupMenuHelper.item(c.plusMenu(), "From computer…").doClick()
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
        c.input.caretPosition = 3
        val shiftEnter = key(c, KeyEvent.VK_ENTER, shift = true)
        assertTrue(sent.isEmpty())
        assertTrue("Shift+Enter is handled, not passed on", shiftEnter.isConsumed)
        assertEquals("Shift+Enter adds a line break", "one\n", c.input.text)
        c.input.replaceSelection("two")
        assertEquals("one\ntwo", c.input.text)
        c.input.text = "one"
        val enter = key(c, KeyEvent.VK_ENTER)
        assertTrue(enter.isConsumed)
        assertEquals("one", sent.single().first)
        c.input.text = "two"
        c.keyHandler = { true }
        key(c, KeyEvent.VK_ENTER)
        assertEquals(1, sent.size)
    }

    fun `test Shift Enter breaks the line at the caret and replaces a selection`() {
        val c = composer()
        c.input.text = "hello world"
        c.input.caretPosition = 5
        key(c, KeyEvent.VK_ENTER, shift = true)
        assertEquals("hello\n world", c.input.text)
        c.input.select(0, 5)
        key(c, KeyEvent.VK_ENTER, shift = true)
        assertEquals("\n\n world", c.input.text)
        assertTrue("nothing was sent", sent.isEmpty())
        // Alt+Enter does the same, as it does in other chat boxes.
        c.input.text = "a"
        c.input.caretPosition = 1
        val alt = KeyEvent(c.input, KeyEvent.KEY_PRESSED, 0, KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED)
        c.input.keyListeners.forEach { it.keyPressed(alt) }
        assertEquals("a\n", c.input.text)
    }

    fun `test nothing is sent while disconnected`() {
        val c = Composer(project, store = { null }, onSubmit = { t, i, f -> sent += Triple(t, i, f) })
        c.input.text = "hello"
        c.submitForTest()
        assertTrue(sent.isEmpty())
    }

    fun `test the at button puts an at sign in the box and spaces it after a word`() {
        val c = composer()
        c.atButton().doClick()
        assertEquals("@", c.input.text)
        c.input.text = "look"
        c.input.caretPosition = 4
        c.atButton().doClick()
        assertEquals("look @", c.input.text)
    }

    private fun streamingCore(phase: String) = object : bot.cline.jetbrains.bridge.CoreApi {
        val calls = mutableListOf<Pair<String, String>>()
        override fun call(service: String, method: String, messageJson: String): java.util.concurrent.CompletableFuture<String> {
            calls += service to method
            return java.util.concurrent.CompletableFuture.completedFuture(
                if (method == "getLatestState") com.google.gson.Gson().toJson(mapOf("stateJson" to """{"mode":"act","epoch":1,"stateVersion":1,"turnState":{"phase":"$phase","seq":1},"clineMessages":[]}""")) else "{}",
            )
        }
        override fun subscribe(service: String, method: String, messageJson: String, onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) = bot.cline.jetbrains.bridge.Subscription {}
    }

    fun `test one quote and the box text goes out in the VS Code format and the quote is used once`() {
        val c = composer()
        c.addQuote("  the router contract \n")
        assertEquals(listOf(bot.cline.jetbrains.chat.QuoteEntry("the router contract")), c.quotes)
        c.input.text = "why is that safe?"
        c.submitForTest()
        assertEquals("[context] \n>  the router contract \n[/context] \n\n why is that safe?", sent.single().first)
        assertTrue("the quotes are used once", c.quotes.isEmpty())
    }

    fun `test several quotes pile up each with its own reply and go out together`() {
        val c = composer()
        c.addQuote("first passage", "this one is wrong")
        c.addQuote("second passage")
        c.addQuote("third passage", "and this?")
        assertEquals(listOf("first passage", "second passage", "third passage"), c.quotes.map { it.quote })
        assertEquals(listOf("this one is wrong", "", "and this?"), c.quotes.map { it.reply })
        c.input.text = "please fix them"
        c.submitForTest()
        val expected = "[context] \n>  first passage \n[/context] \n\n this one is wrong\n\n" +
            "[context] \n>  second passage \n[/context]\n\n" +
            "[context] \n>  third passage \n[/context] \n\n and this?\n\nplease fix them"
        assertEquals(expected, sent.single().first)
    }

    fun `test a card shows the reply that was typed and a Reply tag only when there is none`() {
        val c = composer()
        c.addQuote("with a reply", "my words about it")
        c.addQuote("without one")
        fun labels(component: java.awt.Component): List<String> = when (component) {
            is com.intellij.ui.components.JBLabel -> listOf(component.text.orEmpty().replace(Regex("<[^>]+>"), "").trim())
            is java.awt.Container -> component.components.flatMap(::labels)
            else -> emptyList()
        }
        val all = labels(c)
        assertTrue(all.toString(), all.any { it.contains("my words about it") })
        assertTrue(all.toString(), all.count { it == "Reply" } == 1)
    }

    fun `test the same quote with the same reply is not added twice and a blank one is ignored`() {
        val c = composer()
        c.addQuote("same", "r")
        c.addQuote("same", "r")
        c.addQuote("   ")
        assertEquals(1, c.quotes.size)
        c.addQuote("same", "another reply")
        assertEquals(2, c.quotes.size)
    }

    fun `test send is enabled with only quotes and a quote can be removed`() {
        val c = composer()
        assertFalse(c.sendEnabled)
        c.addQuote("something")
        assertTrue("quotes alone can be sent", c.sendEnabled)
        c.addQuote("else")
        c.removeQuote(0)
        assertEquals(listOf("else"), c.quotes.map { it.quote })
        assertTrue(c.sendEnabled)
        c.removeQuote(0)
        assertFalse(c.sendEnabled)
        c.addQuote("again")
        c.clearQuotes()
        assertFalse(c.sendEnabled)
    }

    fun `test reset empties the text the attachments the quotes and the message`() {
        val c = composer()
        c.input.text = "a draft"
        c.addFiles(listOf(file("a.txt"), file("run.exe")))
        c.addQuote("quoted", "with a reply")
        assertFalse(c.statusText.isEmpty())
        c.reset()
        assertEquals("", c.input.text)
        assertTrue(c.attachments.isEmpty)
        assertTrue(c.strip().chipNames.isEmpty())
        assertTrue(c.quotes.isEmpty())
        assertEquals("", c.statusText)
        assertFalse(c.sendEnabled)
    }

    fun `test sending only quotes sends them as context`() {
        val c = composer()
        c.addQuote("a")
        c.addQuote("b", "about b")
        c.submitForTest()
        assertEquals("[context] \n>  a \n[/context]\n\n[context] \n>  b \n[/context] \n\n about b", sent.single().first)
        assertTrue(c.quotes.isEmpty())
    }

    fun `test snapshot of several quote cards`() {
        val c = composer()
        c.addQuote("provider dropdown prints the raw object (my popup got a renderer but this dialog did not)", "Can you fix this one first?")
        c.addQuote("a saved key should show as a masked field")
        c.input.text = "and keep the rest as is"
        Snapshots.save(c, "quote-cards", 520)
        val popup = ReplyPopupPanel("provider dropdown prints the raw object", onSend = {}, onAddToComposer = { _ -> })
        popup.background = ChatColors.card
        popup.isOpaque = true
        Snapshots.save(popup, "reply-popup", 340)
    }

    fun `test while Cline works the send button is a stop button that cancels the task`() {
        val core = streamingCore("streaming")
        val store = bot.cline.jetbrains.chat.ChatStore(core).also { it.start().get() }
        val sent = mutableListOf<String>()
        val c = Composer(project, store = { store }, onSubmit = { t, _, _ -> sent += t }, clipboardImage = { null })
        c.update(true)
        assertTrue(c.isRunning)
        assertTrue("stop is available with an empty box", c.sendEnabled)
        assertEquals("Stop", c.sendButton().toolTipText)
        c.sendButton().click()
        assertEquals("cancelTask", core.calls.last().second)
        assertTrue(sent.isEmpty())
    }

    fun `test when Cline is idle the button sends and is greyed with an empty box`() {
        val core = streamingCore("idle")
        val store = bot.cline.jetbrains.chat.ChatStore(core).also { it.start().get() }
        val sent = mutableListOf<String>()
        val c = Composer(project, store = { store }, onSubmit = { t, _, _ -> sent += t }, clipboardImage = { null })
        c.update(true)
        assertFalse(c.isRunning)
        assertFalse(c.sendEnabled)
        assertEquals("Send", c.sendButton().toolTipText)
        c.input.text = "go"
        assertTrue(c.sendEnabled)
        c.sendButton().click()
        assertEquals(listOf("go"), sent)
        assertTrue(core.calls.none { it.second == "cancelTask" })
    }

    fun `test the mode switch shows the core's mode and choosing sends it`() {
        val core = ModelPopupTest.PlanCore("plan")
        val store = bot.cline.jetbrains.chat.ChatStore(core).also { it.start().get() }
        val c = Composer(project, store = { store }, onSubmit = { _, _, _ -> }, clipboardImage = { null })
        c.update(true)
        assertEquals("Plan", c.modeToggle.selected)
        c.modeToggle.choose("Act")
        val call = core.calls.last()
        assertEquals("togglePlanActModeProto", call.second)
        assertEquals("ACT", com.google.gson.JsonParser.parseString(call.third).asJsonObject.get("mode").asString)
        assertEquals("Act", c.modeToggle.selected)
    }

    fun `test the switch cannot be used while disconnected and showing a mode does not send`() {
        val c = Composer(project, store = { null }, onSubmit = { _, _, _ -> }, clipboardImage = { null })
        c.update(false)
        assertFalse(c.modeToggle.isEnabled)
        c.modeToggle.show("Plan")
        assertEquals("Plan", c.modeToggle.selected)
    }

    fun `test the buttons keep a small fixed size whatever the IDE button style says`() {
        val c = composer()
        val plus = c.findPlus()
        val size = plus.preferredSize
        assertTrue("plus is ${size}", size.width <= 40 && size.height <= 40 && size.width == size.height)
        val send = c.sendButton()
        assertEquals(size, send.preferredSize)
        assertTrue(c.chip.minimumSize.width < c.chip.preferredSize.width)
    }

    fun `test the popup fits inside the tool window`() {
        fun scale(i: Int) = com.intellij.util.ui.JBUI.scale(i)
        assertEquals(scale(300), ModelPopup.widthFor(scale(356)))
        assertEquals(scale(240), ModelPopup.widthFor(scale(200)))
        assertEquals("a wide window does not widen it", scale(300), ModelPopup.widthFor(scale(1400)))
        assertEquals(scale(284), ModelPopup.widthFor(scale(300)))
    }

    fun `test a narrow chip is squeezed with an ellipsis and never overlaps the plus`() {
        val layout = ShrinkRightLayout()
        val parent = javax.swing.JPanel(layout)
        val chip = PillButton("DeepSeek-V4.1-Flash  1M  High  ⌃", { java.awt.Color.LIGHT_GRAY })
        parent.add(chip)
        parent.setSize(120, 40)
        layout.layoutContainer(parent)
        assertTrue("fits: ${chip.bounds}", chip.x >= 0 && chip.x + chip.width <= 120 && chip.width in 60..120)
        parent.setSize(600, 40)
        layout.layoutContainer(parent)
        assertEquals(chip.preferredSize.width, chip.width)
        assertEquals(600, chip.x + chip.width)
    }

    fun `test the text box uses the UI font not the editor font`() {
        val c = composer()
        assertFalse("monospaced: ${c.input.font}", c.input.font.family.contains("Mono", ignoreCase = true) || c.input.font.family.equals("Monospaced"))
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
            Snapshots.save(empty, "composer-narrow", 330)
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

    private fun Composer.findPlus(): PillButton {
        fun walk(c: java.awt.Component): List<java.awt.Component> = listOf(c) + ((c as? java.awt.Container)?.components?.flatMap(::walk) ?: emptyList())
        return walk(this).filterIsInstance<PillButton>().first { it.text == "+" }
    }
}
