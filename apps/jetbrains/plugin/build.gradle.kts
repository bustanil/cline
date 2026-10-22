import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
