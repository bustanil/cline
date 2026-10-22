package bot.cline.jetbrains.ide

import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/** Paints a component to a PNG in the folder named by CLINE_SNAPSHOT_DIR, so a layout can be looked at. Does nothing when it is unset. */
object Snapshots {
    private fun layoutAll(c: Component) {
        if (c is Container) {
            c.invalidate()
            c.doLayout()
            c.components.forEach(::layoutAll)
        }
    }

    fun save(component: JComponent, name: String, width: Int = 760) {
        val dir = System.getenv("CLINE_SNAPSHOT_DIR") ?: return
        UIUtil.dispatchAllInvocationEvents()
        var height = 10
        repeat(8) {
            component.setSize(width, height)
            layoutAll(component)
            height = component.preferredSize.height.coerceAtLeast(30)
        }
        component.setSize(width, height)
        layoutAll(component)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = ChatColors.page
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        component.paint(g)
        g.dispose()
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }
}
