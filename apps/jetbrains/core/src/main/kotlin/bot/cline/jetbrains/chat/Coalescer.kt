package bot.cline.jetbrains.chat

/**
 * Runs [run] at most once per [intervalMs], however often [request] is called. While a reply streams the core
 * pushes an update for nearly every word; drawing each one makes the chat flicker, so the draws are batched.
 *
 * The first request after a quiet spell runs at once, so a single update is not delayed. Requests that arrive
 * during the interval share one later run, and that run always happens after the last request, so the screen
 * never ends on a stale state. This is the same idea as Cline's `StatePostDebouncer`.
 *
 * [request] may be called from any thread. [schedule] must run its task later (here, on the UI thread) and
 * [run] is called from that task.
 */
class Coalescer(
    private val intervalMs: Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val run: () -> Unit,
) {
    private var pending = false
    private var lastRun = Long.MIN_VALUE / 2

    /** How many times [run] has been called. */
    @Volatile
    var runs = 0
        private set

    fun request() {
        val delay: Long
        synchronized(this) {
            if (pending) return
            pending = true
            delay = (intervalMs - (now() - lastRun)).coerceIn(0, intervalMs)
        }
        schedule(delay) {
            synchronized(this) {
                pending = false
                lastRun = now()
            }
            runs++
            run()
        }
    }
}
