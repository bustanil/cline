package bot.cline.jetbrains.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescerTest {
    /** A clock and a scheduler the test drives by hand. */
    private class Fake {
        var time = 1_000_000L
        val queue = mutableListOf<Pair<Long, () -> Unit>>() // due time and task

        fun schedule(delay: Long, task: () -> Unit) { queue += (time + delay) to task }

        /** Moves time forward and runs what is due, in order. */
        fun advance(ms: Long) {
            time += ms
            while (true) {
                val due = queue.filter { it.first <= time }.minByOrNull { it.first } ?: break
                queue.remove(due)
                due.second()
            }
        }
    }

    private fun coalescer(fake: Fake, onRun: () -> Unit) = Coalescer(50, fake::schedule, { fake.time }, onRun)

    @Test
    fun `the first request after a quiet spell runs without waiting`() {
        val fake = Fake()
        var runs = 0
        val c = coalescer(fake) { runs++ }
        c.request()
        fake.advance(0)
        assertEquals(1, runs)
    }

    @Test
    fun `a burst shares one later run and that run comes after the last request`() {
        val fake = Fake()
        val states = mutableListOf<Int>()
        var latest = 0
        val c = coalescer(fake) { states += latest }
        latest = 1; c.request(); fake.advance(0)           // runs now, sees 1
        for (n in 2..20) { latest = n; c.request(); fake.advance(1) } // 19 requests in 19 ms
        assertEquals(listOf(1), states)                       // none ran yet
        fake.advance(60)
        assertEquals(listOf(1, 20), states)                   // one run, with the final state
    }

    @Test
    fun `a hundred rapid requests cause only a handful of runs`() {
        val fake = Fake()
        val c = coalescer(fake) { }
        repeat(100) { c.request(); fake.advance(5) }          // 500 ms of updates every 5 ms
        fake.advance(100)
        assertEquals(true, c.runs in 8..12, "runs=${c.runs}")
    }

    @Test
    fun `after the interval has passed a new request runs at once again`() {
        val fake = Fake()
        var runs = 0
        val c = coalescer(fake) { runs++ }
        c.request(); fake.advance(0)
        fake.advance(500)
        c.request(); fake.advance(0)
        assertEquals(2, runs)
    }

    @Test
    fun `a request made while a run is pending adds no second run`() {
        val fake = Fake()
        var runs = 0
        val c = coalescer(fake) { runs++ }
        c.request(); fake.advance(0)
        c.request(); c.request(); c.request()
        fake.advance(200)
        assertEquals(2, runs)
    }
}
