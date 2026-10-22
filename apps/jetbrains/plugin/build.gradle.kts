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
        name = "Cline"
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
