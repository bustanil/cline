package bot.cline.jetbrains.bridge

/** One file in a multi-file diff. */
data class FileDiff(val filePath: String, val left: String, val right: String)

/** What the core asked the IDE to tell the user. */
data class UserMessage(val type: String, val text: String, val items: List<String>, val detail: String?)

/**
 * What the core asks of the IDE. The plugin implements this against the IntelliJ
 * Platform; the defaults suit a headless host, so tests override only what they use.
 * Throw [UnsupportedOperationException] for a call the IDE cannot do.
 */
interface IdeBridge {
    /** Content roots of the open project. */
    fun workspacePaths(): List<String> = emptyList()

    /** Shows [message] and returns the chosen option, or null if dismissed. */
    fun showMessage(message: UserMessage): String? = null

    /** Opens [path] and, when [startLine] is set (1-based), moves there and selects through [endLine]. */
    fun showTextDocument(path: String, startLine: Int? = null, endLine: Int? = null): Unit =
        throw UnsupportedOperationException("showTextDocument")

    fun openTabs(): List<String> = emptyList()

    fun visibleTabs(): List<String> = emptyList()

    fun activeEditor(): String? = null

    /** Shows a diff window with a list of files. */
    fun openMultiFileDiff(title: String?, diffs: List<FileDiff>): Unit = throw UnsupportedOperationException("openMultiFileDiff")

    fun closeAllDiffs() {}

    fun saveDocumentIfDirty(path: String): Boolean = false

    fun clipboardRead(): String = throw UnsupportedOperationException("clipboardRead")

    fun clipboardWrite(text: String): Unit = throw UnsupportedOperationException("clipboardWrite")

    fun openExternal(url: String): Unit = throw UnsupportedOperationException("openExternal")

    /** Product name and version, such as "IntelliJ IDEA" and "2024.3". */
    fun hostVersion(): Pair<String, String> = "IntelliJ IDEA" to "unknown"

    /** The core asked the host bridge to shut down. */
    fun shutdownRequested() {}
}
