package bot.cline.jetbrains.ide

import com.intellij.openapi.util.io.FileUtil
import junit.framework.TestCase

class BundledNodeTest : TestCase() {
    fun `test the node shipped for this platform is found and made executable`() {
        val dir = FileUtil.createTempDirectory("plugin", null)
        val file = java.io.File(dir, "node/darwin-arm64/node").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n") }
        file.setExecutable(false)
        assertEquals(file, CoreLocator.bundledNode(dir, "darwin-arm64"))
        assertTrue(file.canExecute())
        assertNull("another platform's node is not used", CoreLocator.bundledNode(dir, "linux-x64"))
        assertNull(CoreLocator.bundledNode(dir, null))
        assertNull(CoreLocator.bundledNode(null, "darwin-arm64"))
    }

    fun `test a Windows target looks for node dot exe`() {
        val dir = FileUtil.createTempDirectory("plugin", null)
        val file = java.io.File(dir, "node/win-x64/node.exe").apply { parentFile.mkdirs(); writeText("x") }
        file.setExecutable(true)
        assertEquals(file, CoreLocator.bundledNode(dir, "win-x64"))
    }
}
