package bot.cline.jetbrains.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.io.File

/** Finds the Node executable and `cline-core.js` to start. */
object CoreLocator {
    const val PLUGIN_ID = "bot.cline.jetbrains"

    /** A GUI-launched IDE does not inherit the shell's PATH, so look in the usual install places too. */
    private val nodeCandidates = listOf(
        "/opt/homebrew/bin/node",
        "/usr/local/bin/node",
        "/usr/bin/node",
        "/snap/bin/node",
    )

    fun node(settings: ClineSettings.State = ClineSettings.getInstance().state): String? {
        settings.nodePath.takeIf { it.isNotBlank() }?.let { return it.takeIf { path -> File(path).canExecute() } }
        System.getenv("CLINE_NODE")?.takeIf { File(it).canExecute() }?.let { return it }
        pathEntries().map { File(it, "node") }.firstOrNull { it.canExecute() }?.let { return it.path }
        nvmNodes().firstOrNull()?.let { return it.path }
        return nodeCandidates.firstOrNull { File(it).canExecute() }
    }

    fun coreJs(settings: ClineSettings.State = ClineSettings.getInstance().state): File? {
        settings.corePath.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isFile) }
        System.getenv("CLINE_CORE_JS")?.let { return File(it).takeIf(File::isFile) }
        val pluginDir = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.toFile()
        return pluginDir?.let { File(it, "core/cline-core.js") }?.takeIf(File::isFile)
    }

    private fun pathEntries(): List<String> = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()

    private fun nvmNodes(): List<File> {
        val home = System.getProperty("user.home") ?: return emptyList()
        return listOf("$home/.nvm/versions/node", "$home/.local/share/nvm")
            .map(::File)
            .filter(File::isDirectory)
            .flatMap { dir -> dir.listFiles()?.sortedDescending().orEmpty() }
            .map { File(it, "bin/node") }
            .filter(File::canExecute)
    }
}
