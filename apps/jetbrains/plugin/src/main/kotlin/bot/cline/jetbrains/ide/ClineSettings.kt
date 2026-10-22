package bot.cline.jetbrains.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Where to find Node and `cline-core.js`. Blank means "look in the usual places". */
@Service(Service.Level.APP)
@State(name = "ClineSettings", storages = [Storage("cline.xml")])
class ClineSettings : PersistentStateComponent<ClineSettings.State> {
    data class State(
        var nodePath: String = "",
        var corePath: String = "",
        /** Data folder; blank uses the shared `~/.cline`. */
        var dataDir: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ClineSettings = ApplicationManager.getApplication().getService(ClineSettings::class.java)
    }
}
