package bot.cline.jetbrains.bridge

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How to start `cline-core`. */
data class CoreLaunch(
    /** The Node executable. The core's native modules target Node 22.15.0. */
    val node: String,
    /** `cline-core.js` from the standalone build. */
    val coreJs: File,
    /** Data folder; null uses the shared `~/.cline`. */
    val dataDir: File? = null,
    val ide: IdeBridge = object : IdeBridge {},
    /** Extra environment for the core, such as `NODE_PATH` for native modules. */
    val env: Map<String, String> = emptyMap(),
    /** Where the core's output goes. */
    val logFile: File? = null,
    /** First restart delay; it doubles up to [maxRestartDelayMs]. */
    val initialRestartDelayMs: Long = 1_000,
    val maxRestartDelayMs: Long = 30_000,
    /** A connection that lasts this long resets the restart delay. */
    val stableAfterMs: Long = 30_000,
)

/**
 * Starts `cline-core`, serves the host bridge it connects back to, and starts
 * the core again, with growing delays, whenever it exits or the stream drops.
 * Each start gets a fresh token, instance id and port.
 */
class CoreHost(private val launch: CoreLaunch) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cline-core-host").apply { isDaemon = true } }
    private val connectedListeners = CopyOnWriteArrayList<(CoreConnection) -> Unit>()
    private val disconnectedListeners = CopyOnWriteArrayList<() -> Unit>()
    private val random = SecureRandom()

    private var process: Process? = null
    private var server: CoreConnectionServer? = null
    private var restart: ScheduledFuture<*>? = null
    private var delayMs = launch.initialRestartDelayMs
    private var connectedAt = 0L
    private var stopped = true

    @Volatile
    var connection: CoreConnection? = null
        private set

    /** Number of times the core has been started, including restarts. */
    @Volatile
    var starts: Int = 0
        private set

    fun onConnected(listener: (CoreConnection) -> Unit) {
        connectedListeners += listener
    }

    fun onDisconnected(listener: () -> Unit) {
        disconnectedListeners += listener
    }

    @Synchronized
    fun start() {
        if (!stopped) return
        stopped = false
        spawn()
    }

    @Synchronized
    private fun spawn() {
        if (stopped) return
        starts++
        val token = secret()
        val srv = CoreConnectionServer(token, launch.ide) { conn -> handleConnected(conn) }.start()
        server = srv
        try {
            val builder = ProcessBuilder(launch.node, launch.coreJs.path).redirectErrorStream(true)
            launch.logFile?.let { builder.redirectOutput(ProcessBuilder.Redirect.appendTo(it)) }
                ?: builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.environment().apply {
                put("CLINE_CORE_CONNECTION_TOKEN", token)
                put("CLINE_CORE_INSTANCE_ID", "jetbrains-" + UUID.randomUUID())
                put("HOST_BRIDGE_ADDRESS", "127.0.0.1:${srv.port}")
                putAll(launch.env)
                launch.dataDir?.let { put("CLINE_DIR", it.path) }
            }
            val started = builder.start()
            process = started
            started.onExit().thenRun { handleExit(started) }
        } catch (e: Exception) {
            srv.stop()
            server = null
            scheduleRestart()
        }
    }

    private fun handleConnected(conn: CoreConnection) {
        synchronized(this) {
            if (stopped) return
            connection = conn
            connectedAt = System.currentTimeMillis()
        }
        connectedListeners.forEach { it(conn) }
    }

    private fun handleExit(exited: Process) {
        synchronized(this) {
            if (process !== exited) return
            process = null
            server?.stop()
            server = null
        }
        val wasConnected = synchronized(this) { connection.also { connection = null } }
        if (wasConnected != null) disconnectedListeners.forEach { it() }
        scheduleRestart()
    }

    @Synchronized
    private fun scheduleRestart() {
        if (stopped) return
        // A connection that stayed up long enough counts as healthy: start over with the short delay.
        if (connectedAt != 0L && System.currentTimeMillis() - connectedAt >= launch.stableAfterMs) {
            delayMs = launch.initialRestartDelayMs
        }
        connectedAt = 0L
        val wait = delayMs
        delayMs = minOf(delayMs * 2, launch.maxRestartDelayMs)
        restart = scheduler.schedule({ spawn() }, wait, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        stopped = true
        restart?.cancel(false)
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        server?.stop()
        server = null
        connection = null
    }

    override fun close() {
        stop()
        scheduler.shutdownNow()
    }

    private fun secret(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
