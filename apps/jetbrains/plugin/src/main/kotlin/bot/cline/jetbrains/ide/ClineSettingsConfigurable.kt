package bot.cline.jetbrains.ide

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Settings > Tools > Cline Native. */
class ClineSettingsConfigurable : Configurable {
    private val node = TextFieldWithBrowseButton().apply { addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Node Executable")) }
    private val core = TextFieldWithBrowseButton().apply { addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("cline-core.js")) }
    private val data = TextFieldWithBrowseButton().apply { addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Data Folder")) }

    override fun getDisplayName() = "Cline Native"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Node executable (blank: find automatically):", node)
        .addLabeledComponent("cline-core.js (blank: use the bundled copy):", core)
        .addLabeledComponent("Data folder (blank: ~/.cline):", data)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    override fun isModified(): Boolean {
        val s = ClineSettings.getInstance().state
        return node.text != s.nodePath || core.text != s.corePath || data.text != s.dataDir
    }

    override fun apply() {
        val s = ClineSettings.getInstance().state
        s.nodePath = node.text.trim()
        s.corePath = core.text.trim()
        s.dataDir = data.text.trim()
        ProjectManager.getInstance().openProjects.forEach { ClineProjectService.getInstance(it).restart() }
    }

    override fun reset() {
        val s = ClineSettings.getInstance().state
        node.text = s.nodePath
        core.text = s.corePath
        data.text = s.dataDir
    }
}
