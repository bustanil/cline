package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import kotlin.math.ceil

/** How full the context window is: under half is fine, past 80% is close to the limit. */
enum class UsageLevel {
    /** Below 50%. */
    LOW,

    /** 50% to 80%, both ends included. */
    MEDIUM,

    /** Above 80%. */
    HIGH,
    ;

    companion object {
        fun of(fraction: Double): UsageLevel = when {
            fraction < 0.5 -> LOW
            fraction <= 0.8 -> MEDIUM
            else -> HIGH
        }
    }
}

/** What the last request put in the model's context, against how much the model can hold. */
data class ContextUsage(val usedTokens: Long, val windowTokens: Long) {
    /** 0.0 to 1.0, or a little over when the window is overfull. */
    val fraction: Double get() = if (windowTokens <= 0) 0.0 else usedTokens.toDouble() / windowTokens

    /** Whole percent, rounded down so 79.9% does not read as 80%. */
    val percent: Int get() = (fraction * 100).toInt()

    val level: UsageLevel get() = UsageLevel.of(fraction)

    /** `223.6k of 1M tokens (22%)`. */
    fun describe(): String = "${tokens(usedTokens)} of ${tokens(windowTokens)} tokens ($percent%)"

    companion object {
        /**
         * The usage after the last request, as the VS Code client counts it: the tokens that request sent and got
         * back (input, output and both cache counts), shrunk by any compaction since. Null when no request has
         * reported tokens yet or the window is unknown.
         */
        fun of(messages: List<ChatMessage>, windowTokens: Long?): ContextUsage? {
            val window = windowTokens?.takeIf { it > 0 } ?: return null
            val used = lastRequestTokens(Transcript.combineApiRequests(messages)).takeIf { it > 0 } ?: return null
            return ContextUsage(used, window)
        }

        /** Walks back from the end: a completed compaction scales what came before it, and the last request with tokens sets the total. */
        fun lastRequestTokens(messages: List<ChatMessage>): Long {
            var shrink: Double? = null
            for (m in messages.asReversed()) {
                if (m.raw.get("type")?.asString != "say" || m.text().isEmpty()) continue
                val info = runCatching { JsonParser.parseString(m.text()).asJsonObject }.getOrNull() ?: continue
                if (m.say() == "compaction" && info.get("status")?.asString == "completed") {
                    val before = info.get("tokensBefore")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                    val after = info.get("tokensAfter")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                    if (before > 0 && after > 0) shrink = (shrink ?: 1.0) * (after / before)
                }
                if (m.say() == "api_req_started") {
                    fun n(name: String) = info.get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                    val total = n("tokensIn") + n("tokensOut") + n("cacheWrites") + n("cacheReads")
                    if (total > 0) return if (shrink == null) total else ceil(total * shrink).toLong()
                }
            }
            return 0
        }

        private fun tokens(n: Long): String = when {
            n >= 1_000_000 -> String.format("%s", trim(n / 1_000_000.0)) + "M"
            n >= 1_000 -> trim(n / 1_000.0) + "k"
            else -> n.toString()
        }

        private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format("%.1f", v)
    }
}
