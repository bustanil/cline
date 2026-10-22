package bot.cline.jetbrains.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextUsageTest {
    private fun req(ts: Long, json: String) = say(ts, "api_req_started", json)

    @Test
    fun `the level is green under half yellow from half to eighty and red above`() {
        assertEquals(UsageLevel.LOW, UsageLevel.of(0.0))
        assertEquals(UsageLevel.LOW, UsageLevel.of(0.499))
        assertEquals(UsageLevel.MEDIUM, UsageLevel.of(0.5))
        assertEquals(UsageLevel.MEDIUM, UsageLevel.of(0.8))
        assertEquals(UsageLevel.HIGH, UsageLevel.of(0.8001))
        assertEquals(UsageLevel.HIGH, UsageLevel.of(1.4))
    }

    @Test
    fun `usage counts the last request with all four token counts`() {
        val usage = ContextUsage.of(
            listOf(req(1, """{"tokensIn":100,"tokensOut":10}"""), req(2, """{"tokensIn":200000,"tokensOut":2800,"cacheWrites":5000,"cacheReads":15800}""")),
            1_000_000,
        )!!
        assertEquals(223_600L, usage.usedTokens)
        assertEquals(22, usage.percent)
        assertEquals(UsageLevel.LOW, usage.level)
        assertEquals("223.6k of 1M tokens (22%)", usage.describe())
    }

    @Test
    fun `a request that has not reported tokens yet is skipped`() {
        val usage = ContextUsage.of(listOf(req(1, """{"tokensIn":5000}"""), req(2, """{"request":"started"}""")), 10_000)!!
        assertEquals(5000L, usage.usedTokens)
        assertEquals(50, usage.percent)
        assertEquals(UsageLevel.MEDIUM, usage.level)
    }

    @Test
    fun `a finished request is merged before it is read`() {
        val usage = ContextUsage.of(listOf(req(1, """{"request":"x"}"""), say(2, "api_req_finished", """{"tokensIn":81000,"tokensOut":1000}""")), 100_000)!!
        assertEquals(82_000L, usage.usedTokens)
        assertEquals(UsageLevel.HIGH, usage.level)
    }

    @Test
    fun `a completed compaction scales what came before it and a failed one does not`() {
        val before = req(1, """{"tokensIn":100000}""")
        val compacted = say(2, "compaction", """{"status":"completed","tokensBefore":100000,"tokensAfter":25000}""")
        assertEquals(25_000L, ContextUsage.of(listOf(before, compacted), 200_000)!!.usedTokens)
        val failed = say(2, "compaction", """{"status":"failed","tokensBefore":100000,"tokensAfter":25000}""")
        assertEquals(100_000L, ContextUsage.of(listOf(before, failed), 200_000)!!.usedTokens)
        // A request after the compaction counts as it is.
        assertEquals(30_000L, ContextUsage.of(listOf(before, compacted, req(3, """{"tokensIn":30000}""")), 200_000)!!.usedTokens)
    }

    @Test
    fun `no usage without a request or without a known window`() {
        assertNull(ContextUsage.of(emptyList(), 1_000_000))
        assertNull(ContextUsage.of(listOf(req(1, """{"tokensIn":10}""")), null))
        assertNull(ContextUsage.of(listOf(req(1, """{"tokensIn":10}""")), 0))
        assertEquals(0, ContextUsage(10, 0).percent)
    }

    @Test
    fun `percent rounds down so 79 point 9 does not look like 80`() {
        assertEquals(79, ContextUsage(799, 1000).percent)
        assertEquals(UsageLevel.MEDIUM, ContextUsage(799, 1000).level)
    }
}
