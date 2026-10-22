package bot.cline.jetbrains.ide

import bot.cline.jetbrains.bridge.CoreHost
import bot.cline.jetbrains.bridge.CoreLaunch
import bot.cline.jetbrains.chat.ChatStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the `cline-core` process for one project and the chat store for its
 * current connection. The tool window reads [status] and [store] and redraws
 * when a listener fires.
 */
@Service(Service.Level.PROJECT)
class ClineProjectService(private val project: Project) : Disposable {
    enum class Status { NOT_STARTED, STARTING, CONNECTED, MISSING_NODE, MISSING_CORE, WRONG_NODE }

    /** Set when [status] is [Status.WRONG_NODE]: the Node found and the version the bundled core needs. */
    @Volatile
    var nodeProblem: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val bridge = IntelliJBridge(project)
    private var host: CoreHost? = null

    @Volatile
    var status: Status = Status.NOT_STARTED
        private set

    @Volatile
    var store: ChatStore? = null
        private set

    private val inputListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val pendingInput = StringBuilder()

    /** Text the core wants in the input box (code added from the editor). It waits here if no chat is open yet. */
    fun addInputListener(listener: (String) -> Unit) {
        inputListeners += listener
        val waiting = synchronized(pendingInput) { pendingInput.toString().also { pendingInput.setLength(0) } }
        if (waiting.isNotEmpty()) listener(waiting)
    }

    fun removeInputListener(listener: (String) -> Unit) {
        inputListeners -= listener
    }

    private fun deliverInput(text: String) {
        if (inputListeners.isEmpty()) {
            synchronized(pendingInput) { if (pendingInput.isNotEmpty()) pendingInput.append("\n\n"); pendingInput.append(text) }
        } else {
            inputListeners.forEach { it(text) }
        }
    }

    private val newChatListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Runs when a new chat is started from outside the chat panel, such as the + in the title bar. */
    fun addNewChatListener(listener: () -> Unit) {
        newChatListeners += listener
    }

    fun removeNewChatListener(listener: () -> Unit) {
        newChatListeners -= listener
    }

    /**
     * Starts a new chat: the core drops the task, and every chat panel empties its composer, so a draft, an
     * attachment or a quote from the old chat does not follow into the new one.
     */
    fun newChat(): java.util.concurrent.CompletableFuture<String>? {
        val started = store?.clearTask()
        newChatListeners.forEach { it() }
        return started
    }

    /** Completes with the chat store once the core is connected, or fails after [timeoutSeconds]. */
    fun awaitStore(timeoutSeconds: Long = 60): java.util.concurrent.CompletableFuture<ChatStore> {
        start()
        store?.let { return java.util.concurrent.CompletableFuture.completedFuture(it) }
        val future = java.util.concurrent.CompletableFuture<ChatStore>()
        lateinit var waiter: () -> Unit
        waiter = {
            store?.let { found ->
                if (future.complete(found)) removeListener(waiter)
            }
        }
        addListener(waiter)
        store?.let { waiter() }
        return future.orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS).whenComplete { _, _ -> removeListener(waiter) }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Starts the core if it is not running. Safe to call again, for example after fixing the settings. */
    @Synchronized
    fun start() {
        if (host != null) return
        val settings = ClineSettings.getInstance().state
        val node = CoreLocator.node(settings)
        if (node == null) return fail(Status.MISSING_NODE)
        val coreJs = CoreLocator.coreJs(settings)
        if (coreJs == null) return fail(Status.MISSING_CORE)
        val env = CoreLocator.environmentFor(coreJs)
        // The native modules shipped with the core are built for one Node version; a different one fails on load.
        if (env.isNotEmpty()) {
            val major = CoreLocator.nodeMajor(node)
            if (major != null && major != CoreLocator.REQUIRED_NODE_MAJOR) {
                nodeProblem = "Found Node $major at $node. The core in this plugin needs Node ${CoreLocator.REQUIRED_NODE_MAJOR}."
                return fail(Status.WRONG_NODE)
            }
        }
        nodeProblem = null

        val created = CoreHost(
            CoreLaunch(
                node = node,
                coreJs = coreJs,
                dataDir = settings.dataDir.takeIf { it.isNotBlank() }?.let(::File),
                ide = bridge,
                env = env,
                logFile = File(PathManager.getLogPath(), "cline-core.log"),
                log = { LOG.info("[${project.name}] $it") },
            ),
        )
        created.onConnected { connection ->
            val chat = ChatStore(connection)
            chat.addListener { fire() }
            chat.addAddToInputListener(::deliverInput)
            store = chat
            status = Status.CONNECTED
            chat.start().whenComplete { _, error -> if (error != null) LOG.warn("Cline: loading state failed", error) }
            fire()
        }
        created.onDisconnected {
            store?.stop()
            store = null
            status = Status.STARTING
            fire()
        }
        host = created
        status = Status.STARTING
        created.start()
        fire()
    }

    /** Stops the core and starts it again, for example after changing the settings. */
    @Synchronized
    fun restart() {
        LOG.info("Cline: restarting the core for ${project.name} (settings changed)")
        host?.close()
        host = null
        store = null
        status = Status.NOT_STARTED
        start()
    }

    private fun fail(reason: Status) {
        status = reason
        fire()
    }

    private fun fire() = listeners.forEach { it() }

    @Synchronized
    override fun dispose() {
        LOG.info("Cline: disposing the service for ${project.name}")
        host?.close()
        host = null
        // A store from the closed connection must not be handed out again if the service starts anew.
        store?.stop()
        store = null
        status = Status.NOT_STARTED
    }

    companion object {
        private val LOG = logger<ClineProjectService>()

        fun getInstance(project: Project): ClineProjectService = project.service()
    }
}
