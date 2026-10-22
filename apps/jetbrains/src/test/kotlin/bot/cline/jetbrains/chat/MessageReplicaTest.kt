package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MessageReplicaTest {
    private fun msg(ts: Long, seq: Int = 0, epoch: Int = 0, text: String = "t$ts") =
        ChatMessage(JsonObject().apply { addProperty("ts", ts); addProperty("seq", seq); addProperty("epoch", epoch); addProperty("text", text) })

    private fun ReplicaState.texts() = messages.map { it.raw.get("text").asString }

    @Test
    fun `appends new ts and replaces an equal or newer seq`() {
        var s = ReplicaState.empty().applyMessage(msg(1, 1, text = "a")).applyMessage(msg(2, 1))
        s = s.applyMessage(msg(1, 2, text = "a2"))
        assertEquals(listOf("a2", "t2"), s.texts())
    }

    @Test
    fun `a lower seq copy is ignored`() {
        val s = ReplicaState.empty().applyMessage(msg(1, 5, text = "new"))
        assertSame(s, s.applyMessage(msg(1, 2, text = "old")))
    }

    @Test
    fun `older epoch is dropped`() {
        val s = ReplicaState.empty().applyMessage(msg(1, epoch = 3))
        assertSame(s, s.applyMessage(msg(2, epoch = 2)))
    }

    @Test
    fun `a lone newer epoch message keeps the transcript`() {
        val s = ReplicaState.empty().applyMessage(msg(1, epoch = 1)).applyMessage(msg(2, epoch = 2))
        assertEquals(2, s.epoch)
        assertEquals(listOf("t1", "t2"), s.texts())
    }

    @Test
    fun `a newer epoch snapshot replaces the transcript`() {
        val s = ReplicaState.empty().applyMessage(msg(1, epoch = 1)).applyStateSnapshot(listOf(msg(9, epoch = 2)), 2, 1)
        assertEquals(listOf("t9"), s.texts())
    }

    @Test
    fun `snapshot after a carried forward partial replaces old rows and keeps the partial`() {
        var s = ReplicaState.empty().applyMessage(msg(1, epoch = 1)).applyMessage(msg(5, 3, epoch = 2, text = "partial"))
        s = s.applyStateSnapshot(listOf(msg(5, 1, epoch = 2, text = "snap"), msg(6, epoch = 2)), 2, 4)
        assertEquals(listOf("partial", "t6"), s.texts())
    }

    @Test
    fun `a same epoch snapshot never drops a message the partial stream delivered`() {
        var s = ReplicaState.empty().applyStateSnapshot(listOf(msg(1)), 1, 1)
        s = s.applyMessage(msg(2, epoch = 1))
        s = s.applyStateSnapshot(listOf(msg(1, epoch = 1)), 1, 2)
        assertEquals(listOf("t1", "t2"), s.texts())
    }

    @Test
    fun `an older snapshot version is ignored`() {
        val s = ReplicaState.empty().applyStateSnapshot(listOf(msg(1, text = "v2")), 0, 2)
        val after = s.applyStateSnapshot(listOf(msg(1, 9, text = "v1")), 0, 1)
        assertEquals(listOf("v2"), after.texts())
    }

    @Test
    fun `a late snapshot cannot revert a newer turn state`() {
        var s = ReplicaState.empty().applyStateSnapshot(emptyList(), 0, 1, TurnState("streaming", null, 5))
        s = s.applyStateSnapshot(emptyList(), 0, 2, TurnState("idle", null, 3))
        assertEquals("streaming", s.turnState?.phase)
        s = s.applyStateSnapshot(emptyList(), 0, 3, TurnState("idle", null, 6))
        assertEquals("idle", s.turnState?.phase)
    }
}
