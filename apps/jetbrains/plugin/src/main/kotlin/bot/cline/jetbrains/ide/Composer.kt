package bot.cline.jetbrains.ide

import bot.cline.jetbrains.chat.AttachResult
import bot.cline.jetbrains.chat.Attachment
import bot.cline.jetbrains.chat.Attachments
import bot.cline.jetbrains.chat.ChatStore
import bot.cline.jetbrains.chat.ModelDetails
import bot.cline.jetbrains.chat.ModelSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.TransferHandler

/** A rounded card with a fill and a thin border. */
class CardPanel(private val fill: Color, private val line: Color, private val arc: Int = 18) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val a = JBUI.scale(arc)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, a, a)
            g2.color = line
            g2.drawRoundRect(0, 0, width - 1, height - 1, a, a)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * A flat button drawn as a circle or a rounded rectangle, with its own fill. It is not a `JButton`: the IDE's
 * button UI gives every `JButton` a minimum size and padding, which turned small round buttons into big discs.
 */
class PillButton(text: String, private val fill: () -> Color, private val round: Boolean = false, private val textColor: () -> Color = { UIUtil.getLabelForeground() }) : JComponent() {
    private val listeners = mutableListOf<java.awt.event.ActionListener>()
    private var pressed = false
    private var hover = false

    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.label()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) {
                val inside = contains(e.point)
                pressed = false
                repaint()
                if (inside && isEnabled) doClick()
            }
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
        })
    }

    fun addActionListener(l: java.awt.event.ActionListener) {
        listeners += l
    }

    fun addActionListener(l: () -> Unit) {
        listeners += java.awt.event.ActionListener { l() }
    }

    fun doClick() {
        if (!isEnabled) return
        val event = java.awt.event.ActionEvent(this, java.awt.event.ActionEvent.ACTION_PERFORMED, text)
        listeners.toList().forEach { it.actionPerformed(event) }
    }

    override fun getPreferredSize(): Dimension {
        if (round) return Dimension(JBUI.scale(30), JBUI.scale(30))
        val metrics = getFontMetrics(font)
        return Dimension(metrics.stringWidth(text) + JBUI.scale(24), maxOf(metrics.height + JBUI.scale(10), JBUI.scale(28)))
    }

    /** A chip may be squeezed in a narrow window; its text then ends in an ellipsis. */
    override fun getMinimumSize(): Dimension = if (round) preferredSize else Dimension(JBUI.scale(60), preferredSize.height)

    override fun getMaximumSize(): Dimension = preferredSize

    private fun shade(c: Color, factor: Double) = if (factor == 1.0) c else Color((c.red * factor).toInt(), (c.green * factor).toInt(), (c.blue * factor).toInt(), c.alpha)

    private fun fit(text: String, metrics: java.awt.FontMetrics, available: Int): String {
        if (metrics.stringWidth(text) <= available) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > available) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = shade(fill(), if (!isEnabled) 1.0 else if (pressed) 0.9 else if (hover) 0.96 else 1.0)
            val arc = if (round) minOf(width, height) else JBUI.scale(12)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.font = font
            g2.color = if (isEnabled) textColor() else UIUtil.getContextHelpForeground()
            val metrics = g2.fontMetrics
            val shown = fit(text, metrics, width - JBUI.scale(16))
            g2.drawString(shown, (width - metrics.stringWidth(shown)) / 2, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** Puts its one child at the right edge, at its preferred width, or narrower when there is not room. */
class ShrinkRightLayout : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component?) {}

    override fun removeLayoutComponent(comp: java.awt.Component?) {}

    override fun layoutContainer(parent: java.awt.Container) {
        val child = parent.components.firstOrNull() ?: return
        val insets = parent.insets
        val available = (parent.width - insets.left - insets.right).coerceAtLeast(0)
        val width = minOf(child.preferredSize.width, available)
        val height = child.preferredSize.height
        child.setBounds(insets.left + available - width, insets.top + ((parent.height - insets.top - insets.bottom - height) / 2).coerceAtLeast(0), width, height)
    }

    override fun preferredLayoutSize(parent: java.awt.Container): Dimension {
        val child = parent.components.firstOrNull() ?: return Dimension()
        val insets = parent.insets
        return Dimension(child.preferredSize.width + insets.left + insets.right, child.preferredSize.height + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: java.awt.Container): Dimension {
        val child = parent.components.firstOrNull() ?: return Dimension()
        val insets = parent.insets
        return Dimension(child.minimumSize.width + insets.left + insets.right, child.minimumSize.height + insets.top + insets.bottom)
    }
}

/**
 * The thumbnails and chips for what will be sent with the next message. Each has a button to take it away.
 */
class AttachmentStrip(private val attachments: Attachments, private val onChange: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)) {
    init {
        isOpaque = false
        isVisible = false
    }

    fun refresh() {
        removeAll()
        attachments.items.forEachIndexed { index, attachment -> add(chip(index, attachment)) }
        isVisible = !attachments.isEmpty
        revalidate()
        repaint()
    }

    internal val chipNames: List<String> get() = attachments.items.map { it.name }

    internal fun removeAt(index: Int) {
        attachments.remove(index)
        refresh()
        onChange()
    }

    private fun chip(index: Int, attachment: Attachment): JComponent {
        val chip = RoundedPanel(ChatColors.bubble, 10).apply {
            layout = FlowLayout(FlowLayout.LEFT, 6, 3)
            border = JBUI.Borders.empty(0, 4)
        }
        chip.add(JBLabel(if (attachment.kind == Attachment.Kind.IMAGE) thumbnail(attachment) else com.intellij.icons.AllIcons.FileTypes.Any_type))
        chip.add(JBLabel(attachment.name.let { if (it.length > 24) it.take(21) + "…" else it }).apply { toolTipText = attachment.name })
        chip.add(JBLabel("×").apply {
            foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Remove"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = removeAt(index)
            })
        })
        return chip
    }

    private fun thumbnail(attachment: Attachment): javax.swing.Icon = attachmentThumbnail(attachment, 28)
}

/** A small picture of an attached image, scaled to fit a [size] square; a generic icon if it cannot be decoded (WebP). */
internal fun attachmentThumbnail(attachment: Attachment, size: Int): javax.swing.Icon {
    val bytes = Attachments.decodeDataUrl(attachment.value)?.second
    val image = bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() } ?: return com.intellij.icons.AllIcons.FileTypes.Image
    val box = JBUI.scale(size)
    val scale = minOf(box.toDouble() / image.width, box.toDouble() / image.height)
    return ImageIcon(image.getScaledInstance((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), java.awt.Image.SCALE_SMOOTH))
}

/**
 * The input: a rounded card with the text box, what is attached, a `+` to attach more, the model chip and
 * a send button. [onSubmit] gets the text and the attachments when the user sends.
 */
class Composer(
    private val project: Project,
    private val store: () -> ChatStore?,
    private val onSubmit: (text: String, images: List<String>, files: List<String>) -> Unit,
    private val chooseFiles: () -> List<File> = { pickFilesWithDialog(project) },
) : JPanel(BorderLayout()) {
    val attachments = Attachments()

    /** Gets each key first; return true to keep the text box from seeing it (the `@` suggestions use this). */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    val input = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        // A text area takes the editor's monospaced font from the IDE; the chat uses the UI font.
        font = JBUI.Fonts.label().deriveFont(JBUI.Fonts.label().size2D + 1f)
        border = JBUI.Borders.empty(8, 12, 4, 12)
        emptyText.text = "Message or run a task, @ for files"
    }
    private val strip = AttachmentStrip(attachments) { updateSend() }
    private val plus = PillButton("+", { ChatColors.bubble }, round = true).apply {
        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().size2D + 3f)
        toolTipText = "Attach images or files"
        addActionListener { attach() }
    }
    val chip = PillButton("Model", { ChatColors.bubble }).apply {
        toolTipText = "Provider, model and effort"
        addActionListener { showModelPopup() }
    }
    private val send = PillButton("↑", { if (isSendReady()) JBColor(0x6B8DF0, 0x5B7FEA) else JBColor(0xBFCCF7, 0x3C4A78) }, round = true, textColor = { Color.WHITE }).apply {
        font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().size2D + 2f)
        toolTipText = "Send"
        addActionListener { submit() }
    }
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 12)
        isVisible = false
    }
    private var connected = false
    private var details: ModelDetails? = null
    private var detailsKey = ""

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10)
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 8, 8, 8)
            add(plus, BorderLayout.WEST)
            add(send, BorderLayout.EAST)
            // The chip can shrink, so in a narrow window it loses its text before the buttons lose their room.
            add(JPanel(ShrinkRightLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(0, 8); add(chip) }, BorderLayout.CENTER)
        }
        val card = CardPanel(ChatColors.card, ChatColors.border).apply {
            add(strip, BorderLayout.NORTH)
            add(JBScrollPane(input).apply { border = JBUI.Borders.empty(); isOpaque = false; viewport.isOpaque = false }, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        add(card, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (keyHandler?.invoke(e) == true) return
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !e.isConsumed) {
                    e.consume()
                    submit()
                }
            }
        })
        input.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSend()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSend()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = Unit
        })
        installTransferHandler()
        updateSend()
    }

    private fun isSendReady() = connected && (input.text.isNotBlank() || !attachments.isEmpty)

    private fun updateSend() {
        send.isEnabled = isSendReady()
        send.repaint()
    }

    /** Tells the composer whether the core is connected, what the model is, and whether it takes images. */
    fun update(connected: Boolean) {
        this.connected = connected
        input.isEnabled = connected
        plus.isEnabled = connected
        chip.isEnabled = connected
        updateSend()
        val current = store() ?: return
        val mode = current.mode
        val selection = ModelSettings.current(current.snapshot.extensionState, mode)
        val provider = selection.providerId
        val key = "$provider/$mode/${selection.modelId}"
        if (provider != null && key != detailsKey) {
            detailsKey = key
            current.models.details(provider, mode).whenComplete { found, _ ->
                ApplicationManager.getApplication().invokeLater({
                    if (key == detailsKey) {
                        details = found
                        attachments.imagesAllowed = found?.supportsImages ?: true
                        refreshChip(selection.providerId, selection.modelId)
                    }
                }, ModalityState.any())
            }
        }
        refreshChip(provider, selection.modelId)
    }

    private fun refreshChip(providerId: String?, modelId: String?) {
        val d = details
        val name = d?.name?.takeIf { it.isNotBlank() } ?: modelId?.substringAfterLast('/') ?: "Model"
        val effort = providerId?.let { ClineSettings.getInstance().effortFor(it) }?.let { bot.cline.jetbrains.chat.ReasoningEffort.fromWire(it)?.label }
        val context = d?.contextWindow?.let { bot.cline.jetbrains.chat.ModelOption("", null, it).contextLabel }.orEmpty()
        chip.text = listOf(name, context, effort).filterNot { it.isNullOrEmpty() }.joinToString("  ") + "  ⌃"
        chip.toolTipText = listOfNotNull(providerId, modelId).joinToString(" · ").ifEmpty { "Provider, model and effort" }
        chip.revalidate()
        chip.repaint()
    }

    /** Shows a short message under the card, for example why a file was not added. */
    fun say(message: String?) {
        status.text = message.orEmpty()
        status.isVisible = !message.isNullOrEmpty()
        revalidate()
    }

    private fun attach() {
        addFiles(chooseFiles())
    }

    /** Adds files the user chose or dropped, and reports the ones that were refused. */
    fun addFiles(files: List<File>) {
        val refused = mutableListOf<String>()
        for (file in files) {
            when (val result = attachments.addFile(file)) {
                is AttachResult.Added -> {}
                is AttachResult.Rejected -> refused += "${result.name}: ${result.reason}"
            }
        }
        strip.refresh()
        updateSend()
        say(refused.joinToString("  ").ifEmpty { null })
    }

    /** Adds an image from the clipboard or a drop. */
    fun addImage(image: BufferedImage, name: String = "pasted-image.png") {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        when (val result = attachments.addImageBytes(bytes, "image/png", name)) {
            is AttachResult.Added -> say(null)
            is AttachResult.Rejected -> say("${result.name}: ${result.reason}")
        }
        strip.refresh()
        updateSend()
    }

    private fun installTransferHandler() {
        val base = input.transferHandler
        input.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(DataFlavor.imageFlavor) || base.canImport(support)

            override fun importData(support: TransferSupport): Boolean {
                val transferable = support.transferable
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
                    if (!files.isNullOrEmpty()) {
                        addFiles(files)
                        return true
                    }
                }
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor) && !support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
                    if (image != null) {
                        addImage(toBuffered(image))
                        return true
                    }
                }
                return base.importData(support)
            }

            // Cut, copy and dragging text out are the text box's own; hand them to it.
            override fun getSourceActions(c: JComponent?) = base.getSourceActions(c)

            override fun exportToClipboard(comp: JComponent?, clip: java.awt.datatransfer.Clipboard?, action: Int) = base.exportToClipboard(comp, clip, action)

            override fun exportAsDrag(comp: JComponent?, e: java.awt.event.InputEvent?, action: Int) = base.exportAsDrag(comp, e, action)

            override fun getVisualRepresentation(t: java.awt.datatransfer.Transferable?) = base.getVisualRepresentation(t)
        }
    }

    private fun toBuffered(image: java.awt.Image): BufferedImage {
        if (image is BufferedImage) return image
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return buffered
    }

    private fun submit() {
        if (!isSendReady()) return
        val text = input.text.trim()
        val images = attachments.images
        val files = attachments.files
        input.text = ""
        attachments.clear()
        strip.refresh()
        say(null)
        updateSend()
        onSubmit(text, images, files)
    }

    private fun showModelPopup() {
        val current = store() ?: return
        ModelPopup.show(project, current, chip) { update(connected); detailsKey = ""; update(connected) }
    }

    internal val chipText: String get() = chip.text.orEmpty()

    internal val statusText: String get() = status.text.orEmpty()

    internal fun strip(): AttachmentStrip = strip

    internal fun submitForTest() = submit()

    internal fun sendButton(): PillButton = send

    internal val sendEnabled: Boolean get() = send.isEnabled

    companion object {
        /** The types Cline accepts, in a file chooser. */
        fun chooserDescriptor(): FileChooserDescriptor =
            FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Attach Images or Files")
                .withExtensionFilter("Images and files", *(Attachments.IMAGE_EXTENSIONS + Attachments.FILE_EXTENSIONS).toTypedArray())

        private fun pickFilesWithDialog(project: Project): List<File> =
            FileChooser.chooseFiles(chooserDescriptor(), project, null).map { File(it.path) }
    }
}
