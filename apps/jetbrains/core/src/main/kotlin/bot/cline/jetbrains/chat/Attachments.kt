package bot.cline.jetbrains.chat

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** An image or a file the user adds to a message. */
data class Attachment(
    val kind: Kind,
    /** The name to show. */
    val name: String,
    /** An image: a `data:image/png;base64,…` URL. A file: its absolute path. This is what the core takes. */
    val value: String,
    val sizeBytes: Long,
) {
    enum class Kind { IMAGE, FILE }
}

/** What happened to a file the user tried to add. */
sealed interface AttachResult {
    data class Added(val attachment: Attachment) : AttachResult

    data class Rejected(val name: String, val reason: String) : AttachResult
}

/**
 * The images and files waiting to go with the next message, and the rules Cline's VS Code client
 * applies: which types are accepted, the size limits, and at most [MAX_ITEMS] in all. The core does not
 * check these; a client that skips them can send what the model then refuses.
 */
class Attachments(
    /** False when the selected model cannot take images. */
    var imagesAllowed: Boolean = true,
) {
    private val list = mutableListOf<Attachment>()

    val items: List<Attachment> get() = list.toList()

    val isEmpty: Boolean get() = list.isEmpty()

    /** The `images` list of `newTask` and `askResponse`. */
    val images: List<String> get() = list.filter { it.kind == Attachment.Kind.IMAGE }.map { it.value }

    /** The `files` list of `newTask` and `askResponse`. */
    val files: List<String> get() = list.filter { it.kind == Attachment.Kind.FILE }.map { it.value }

    fun remove(index: Int) {
        if (index in list.indices) list.removeAt(index)
    }

    fun clear() = list.clear()

    /** Adds the file at [file] as an image or a file, or says why not. */
    fun addFile(file: File): AttachResult {
        val name = file.name
        val extension = file.extension.lowercase()
        if (!file.isFile) return AttachResult.Rejected(name, "It is not a file.")
        if (extension in IMAGE_EXTENSIONS) {
            if (!imagesAllowed) return AttachResult.Rejected(name, "The selected model does not accept images.")
            val bytes = runCatching { file.readBytes() }.getOrElse { return AttachResult.Rejected(name, "It could not be read.") }
            return addImageBytes(bytes, mimeFor(extension), name)
        }
        if (extension !in FILE_EXTENSIONS) return AttachResult.Rejected(name, "Files of this type are not accepted. Accepted: ${FILE_EXTENSIONS.sorted().joinToString(", ")}.")
        if (file.length() > MAX_FILE_BYTES) return AttachResult.Rejected(name, "It is larger than 20 MB.")
        return accept(Attachment(Attachment.Kind.FILE, name, file.absolutePath, file.length()))
    }

    /** Adds an image given as bytes, such as one pasted from the clipboard. */
    fun addImageBytes(bytes: ByteArray, mime: String, name: String): AttachResult {
        if (!imagesAllowed) return AttachResult.Rejected(name, "The selected model does not accept images.")
        if (mime !in MIME_TYPES) return AttachResult.Rejected(name, "Only PNG, JPEG and WebP images are accepted.")
        val side = largestSide(bytes)
        if (side != null && side > MAX_IMAGE_SIDE) return AttachResult.Rejected(name, "Its width or height is over $MAX_IMAGE_SIDE pixels.")
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        return accept(Attachment(Attachment.Kind.IMAGE, name, dataUrl, bytes.size.toLong()))
    }

    private fun accept(attachment: Attachment): AttachResult {
        if (list.size >= MAX_ITEMS) return AttachResult.Rejected(attachment.name, "At most $MAX_ITEMS images and files fit in one message.")
        list += attachment
        return AttachResult.Added(attachment)
    }

    /** The longer side in pixels, or null when the format has no decoder here (WebP), in which case the check is skipped. */
    private fun largestSide(bytes: ByteArray): Int? = runCatching {
        val readers = ImageIO.getImageReaders(ImageIO.createImageInputStream(ByteArrayInputStream(bytes)))
        if (!readers.hasNext()) return@runCatching null
        val reader = readers.next()
        ByteArrayInputStream(bytes).use { stream ->
            reader.input = ImageIO.createImageInputStream(stream)
            maxOf(reader.getWidth(0), reader.getHeight(0)).also { reader.dispose() }
        }
    }.getOrNull()

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_IMAGE_SIDE = 7680

        /** Cline's client allows 20,000 KiB. */
        const val MAX_FILE_BYTES = 20_000L * 1024
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        val FILE_EXTENSIONS = setOf("xml", "json", "txt", "log", "md", "docx", "ipynb", "pdf", "xlsx", "csv")
        private val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")

        fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }

        /** The bytes and mime type inside a `data:…;base64,…` URL, or null if it is not one. */
        fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray>? {
            if (!dataUrl.startsWith("data:")) return null
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return null
            val header = dataUrl.substring(5, comma)
            if (!header.endsWith(";base64")) return null
            return runCatching { header.removeSuffix(";base64") to Base64.getDecoder().decode(dataUrl.substring(comma + 1)) }.getOrNull()
        }
    }
}
