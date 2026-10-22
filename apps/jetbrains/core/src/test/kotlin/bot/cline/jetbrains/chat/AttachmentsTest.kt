package bot.cline.jetbrains.chat

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentsTest {
    private val dir = File.createTempFile("attach", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun file(name: String, bytes: ByteArray = "x".toByteArray()) = File(dir, name).also { it.writeBytes(bytes) }

    private fun reason(result: AttachResult) = (result as AttachResult.Rejected).reason

    @Test
    fun `an image becomes a data URL and a file stays a path`() {
        val a = Attachments()
        val image = a.addFile(file("shot.PNG", png(4, 3))) as AttachResult.Added
        assertEquals(Attachment.Kind.IMAGE, image.attachment.kind)
        assertTrue(image.attachment.value.startsWith("data:image/png;base64,"))
        val doc = file("spec.md")
        val added = a.addFile(doc) as AttachResult.Added
        assertEquals(Attachment.Kind.FILE, added.attachment.kind)
        assertEquals(doc.absolutePath, added.attachment.value)
        assertEquals(listOf(image.attachment.value), a.images)
        assertEquals(listOf(doc.absolutePath), a.files)
    }

    @Test
    fun `the data URL decodes back to the image`() {
        val bytes = png(5, 2)
        val a = Attachments()
        val url = (a.addImageBytes(bytes, "image/png", "p.png") as AttachResult.Added).attachment.value
        val (mime, decoded) = assertNotNull(Attachments.decodeDataUrl(url))
        assertEquals("image/png", mime)
        assertTrue(bytes.contentEquals(decoded))
        assertEquals(null, Attachments.decodeDataUrl("https://example.com/a.png"))
        assertEquals(null, Attachments.decodeDataUrl("data:text/plain,hi"))
    }

    @Test
    fun `types outside the accepted lists are rejected with the accepted ones named`() {
        val a = Attachments()
        assertTrue(reason(a.addFile(file("run.exe"))).contains("not accepted") && reason(a.addFile(file("run.exe"))).contains("pdf"))
        assertTrue(a.isEmpty)
        for (ok in listOf("a.xml", "a.json", "a.txt", "a.log", "a.md", "a.docx", "a.ipynb", "a.pdf", "a.xlsx", "a.csv")) assertTrue(a.addFile(file(ok)) is AttachResult.Added, ok)
        assertEquals(10, a.items.size)
    }

    @Test
    fun `a model without image support rejects images but not files`() {
        val a = Attachments(imagesAllowed = false)
        assertTrue(reason(a.addFile(file("i.png", png(2, 2)))).contains("does not accept images"))
        assertTrue(reason(a.addImageBytes(png(2, 2), "image/png", "clip.png")).contains("does not accept images"))
        assertTrue(a.addFile(file("n.txt")) is AttachResult.Added)
    }

    @Test
    fun `an image with a side over 7680 pixels is rejected`() {
        val a = Attachments()
        assertTrue(reason(a.addImageBytes(png(7681, 1), "image/png", "wide.png")).contains("7680"))
        assertTrue(a.addImageBytes(png(7680, 1), "image/png", "ok.png") is AttachResult.Added)
        assertTrue(reason(a.addImageBytes(png(1, 7681), "image/png", "tall.png")).contains("7680"))
    }

    @Test
    fun `a file over 20 MB is rejected and one under it is accepted`() {
        val a = Attachments()
        assertTrue(reason(a.addFile(file("big.log", ByteArray(20_000 * 1024 + 1)))).contains("20 MB"))
        assertTrue(a.addFile(file("fits.log", ByteArray(1024 * 1024))) is AttachResult.Added)
    }

    @Test
    fun `at most twenty fit in a message`() {
        val a = Attachments()
        repeat(20) { assertTrue(a.addFile(file("f$it.txt")) is AttachResult.Added) }
        assertTrue(reason(a.addFile(file("one-more.txt"))).contains("At most 20"))
        a.remove(0)
        assertTrue(a.addFile(file("fits-now.txt")) is AttachResult.Added)
        assertEquals(20, a.items.size)
        a.clear()
        assertTrue(a.isEmpty)
    }

    @Test
    fun `an unknown image format and a missing file are rejected plainly`() {
        val a = Attachments()
        assertTrue(reason(a.addImageBytes(png(1, 1), "image/gif", "a.gif")).contains("PNG, JPEG and WebP"))
        assertFalse(a.addFile(File(dir, "missing.png")) is AttachResult.Added)
    }
}
