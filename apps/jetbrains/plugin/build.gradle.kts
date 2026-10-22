import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.5")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "bot.cline.jetbrains"
        name = "Cline Native"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

// The plugin carries the built cline-core, so only Node is needed on the machine. Build it first with
// `bun esbuild.mjs --standalone` and `node scripts/package-standalone.mjs` in apps/vscode; without
// that, the plugin is built with no core and the settings must point at one.
val coreDist = layout.projectDirectory.dir("../../vscode/dist-standalone")
val extensionManifest = layout.projectDirectory.file("../../vscode/package.json")

tasks.withType<PrepareSandboxTask>().configureEach {
    if (coreDist.file("cline-core.js").asFile.exists()) {
        from(coreDist) {
            into(pluginName.map { "$it/core" })
            exclude("standalone.zip", "**/*.map", "bun.lock", "package-lock.json", "extension/**")
        }
        // The core reads <install dir>/extension/package.json at startup; package-standalone.mjs does not create it.
        from(extensionManifest) {
            into(pluginName.map { "$it/core/extension" })
        }
    }
}

// Node 22 ships inside the plugin so nothing has to be installed. By default only the build machine's platform is
// fetched (about 40 MB compressed); `-PnodePlatforms=darwin-arm64,linux-x64,...` or `-PnodePlatforms=all` adds
// others, and `-PnodePlatforms=none` leaves Node out (the plugin then uses one found on the machine).
// The version matches the native modules in the core (TARGET_NODE_VERSION in apps/vscode/scripts/package-standalone.mjs).
val nodeVersion = "22.15.0"
// Plugin folder name (as in CoreLocator.platformTarget) to the name nodejs.org uses in its file names.
val nodeDistNames = mapOf(
    "darwin-arm64" to "darwin-arm64",
    "darwin-x64" to "darwin-x64",
    "linux-x64" to "linux-x64",
    "linux-aarch64" to "linux-arm64",
    "win-x64" to "win-x64",
)

fun hostNodePlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    return when {
        os.contains("mac") -> if (arm) "darwin-arm64" else "darwin-x64"
        os.contains("win") -> "win-x64"
        else -> if (arm) "linux-aarch64" else "linux-x64"
    }
}

val nodePlatforms: List<String> = when (val wanted = providers.gradleProperty("nodePlatforms").orNull) {
    null, "" -> listOf(hostNodePlatform())
    "none" -> emptyList()
    "all" -> nodeDistNames.keys.toList()
    else -> wanted.split(",").map { it.trim() }.filter { it.isNotEmpty() }.also { list ->
        list.forEach { require(it in nodeDistNames) { "Unknown node platform '$it'; use one of ${nodeDistNames.keys}" } }
    }
}

val nodeDir = layout.buildDirectory.dir("node")

val fetchNode = tasks.register("fetchNode") {
    inputs.property("version", nodeVersion)
    inputs.property("platforms", nodePlatforms)
    outputs.dir(nodeDir)
    doLast {
        val out = nodeDir.get().asFile
        out.deleteRecursively()
        val cache = layout.buildDirectory.dir("node-cache").get().asFile.apply { mkdirs() }
        val base = "https://nodejs.org/dist/v$nodeVersion"
        val sums = cache.resolve("SHASUMS256-$nodeVersion.txt").also { if (!it.isFile) it.writeBytes(URI("$base/SHASUMS256.txt").toURL().readBytes()) }
            .readLines().associate { line -> line.trim().split(Regex("\\s+"), 2).let { it[1] to it[0] } }
        for (platform in nodePlatforms) {
            val dist = nodeDistNames.getValue(platform)
            val windows = platform.startsWith("win")
            val archive = "node-v$nodeVersion-$dist." + if (windows) "zip" else "tar.gz"
            val file = cache.resolve(archive)
            if (!file.isFile) file.writeBytes(URI("$base/$archive").toURL().readBytes())
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == sums[archive]) { "Checksum of $archive does not match nodejs.org's SHASUMS256.txt" }
            val target = out.resolve(platform).apply { mkdirs() }
            if (windows) {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("node-v$nodeVersion-$dist/node.exe")
                    zip.getInputStream(entry).use { input -> target.resolve("node.exe").outputStream().use { input.copyTo(it) } }
                }
            } else {
                val extracted = cache.resolve("x-$platform").apply { deleteRecursively(); mkdirs() }
                val result = ProcessBuilder("tar", "-xzf", file.path, "-C", extracted.path, "node-v$nodeVersion-$dist/bin/node").inheritIO().start().waitFor()
                check(result == 0) { "Could not unpack $archive" }
                val node = extracted.resolve("node-v$nodeVersion-$dist/bin/node")
                node.copyTo(target.resolve("node"), overwrite = true)
                target.resolve("node").setExecutable(true, false)
                extracted.deleteRecursively()
            }
        }
    }
}

tasks.withType<PrepareSandboxTask>().configureEach {
    if (nodePlatforms.isNotEmpty()) {
        dependsOn(fetchNode)
        from(nodeDir) {
            into(pluginName.map { "$it/node" })
            filePermissions { unix("rwxr-xr-x") }
        }
    }
}
