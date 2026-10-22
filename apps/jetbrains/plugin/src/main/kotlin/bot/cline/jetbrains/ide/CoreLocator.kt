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
        bundledNode()?.let { return it.path }
        pathEntries().map { File(it, "node") }.firstOrNull { it.canExecute() }?.let { return it.path }
        nvmNodes().firstOrNull()?.let { return it.path }
        return nodeCandidates.firstOrNull { File(it).canExecute() }
    }

    /** The Node shipped inside the plugin for this platform, if this build has one. Made executable if the install dropped the bit. */
    fun bundledNode(
        pluginDir: File? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.toFile(),
        target: String? = platformTarget(),
    ): File? {
        if (pluginDir == null || target == null) return null
        val node = File(pluginDir, "node/$target/" + if (target.startsWith("win")) "node.exe" else "node")
        if (!node.isFile) return null
        if (!node.canExecute()) node.setExecutable(true, false)
        return node.takeIf { it.canExecute() }
    }

    fun coreJs(settings: ClineSettings.State = ClineSettings.getInstance().state): File? {
        settings.corePath.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isFile) }
        System.getenv("CLINE_CORE_JS")?.let { return File(it).takeIf(File::isFile) }
        return bundledCoreJs()
    }

    /** The core shipped inside the plugin, if this build has one. */
    fun bundledCoreJs(): File? {
        val pluginDir = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.toFile()
        return pluginDir?.let { File(it, "core/cline-core.js") }?.takeIf(File::isFile)
    }

    /** Node major version the bundled native modules are built for (`TARGET_NODE_VERSION` in package-standalone.mjs is 22.15.0). */
    const val REQUIRED_NODE_MAJOR = 22

    /** The folder name under `binaries/` for a platform, or null for one the core is not built for. */
    fun platformTarget(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): String? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"
        val isX64 = arch == "x86_64" || arch == "amd64"
        return when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) "darwin-arm64" else if (isX64) "darwin-x64" else null
            os.contains("win") -> if (isX64) "win-x64" else null
            os.contains("linux") -> if (isArm) "linux-aarch64" else if (isX64) "linux-x64" else null
            else -> null
        }
    }

    /**
     * The `node_modules` with the native modules (`better-sqlite3`) for this platform.
     * The standalone build ships one per platform under `binaries/` and leaves the choice to the installer.
     */
    fun nativeModules(coreJs: File, target: String? = platformTarget()): File? =
        target?.let { File(coreJs.parentFile, "binaries/$it/node_modules") }?.takeIf(File::isDirectory)

    /** Whether `better-sqlite3` is installed in a `node_modules` that Node finds from [coreJs], as in a source checkout. */
    fun hasInstalledNativeModules(coreJs: File): Boolean =
        generateSequence(coreJs.absoluteFile.parentFile) { it.parentFile }.any { File(it, "node_modules/better-sqlite3").isDirectory }

    /**
     * `NODE_PATH` so the core finds the native modules shipped under `binaries/`, keeping anything already set.
     * Empty when the core already finds them, which is the case in a source checkout.
     */
    fun environmentFor(coreJs: File, existingNodePath: String? = System.getenv("NODE_PATH")): Map<String, String> {
        if (hasInstalledNativeModules(coreJs)) return emptyMap()
        val native = nativeModules(coreJs) ?: return emptyMap()
        return mapOf("NODE_PATH" to listOfNotNull(native.path, existingNodePath?.takeIf { it.isNotBlank() }).joinToString(File.pathSeparator))
    }

    /** The major version of the Node at [node], or null if it will not run. */
    fun nodeMajor(node: String): Int? = runCatching {
        val process = ProcessBuilder(node, "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        parseNodeMajor(output)
    }.getOrNull()

    fun parseNodeMajor(version: String): Int? = Regex("""^v?(\d+)\.""").find(version.trim())?.groupValues?.get(1)?.toIntOrNull()

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
