package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProtoMessageConverterTest {
    private fun convert(json: String) = ProtoMessageConverter.convert(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `say kinds and int64 strings convert`() {
        val m = convert("""{"ts":"1700","type":"SAY","say":"TOOL_SAY","text":"{}","seq":"4","epoch":"2","partial":true}""")
        assertEquals("say", m.get("type").asString)
        assertEquals("tool", m.get("say").asString)
        assertEquals(1700L, ChatMessage(m).ts)
        assertEquals(4, ChatMessage(m).seq)
        assertEquals(2, ChatMessage(m).epoch)
        assertEquals(true, m.get("partial").asBoolean)
    }

    @Test
    fun `proto defaults are applied when proto3 json leaves them out`() {
        val ask = convert("""{"ts":"1","type":"ASK"}""")
        assertEquals("followup", ask.get("ask").asString)
        val say = convert("""{"ts":"2","type":"SAY"}""")
        assertEquals("task", say.get("say").asString)
        val defaultType = convert("""{"ts":"3"}""")
        assertEquals("followup", defaultType.get("ask").asString)
    }

    @Test
    fun `empty and zero fields are left out`() {
        val m = convert("""{"ts":"1","type":"SAY","say":"TEXT","text":"","images":[],"seq":"0","epoch":"0","partial":false}""")
        for (field in listOf("text", "images", "seq", "epoch", "partial")) assertFalse(m.has(field), field)
    }

    @Test
    fun `an unknown kind falls back to its lowercase name`() {
        assertEquals("brand_new_kind", convert("""{"ts":"1","type":"SAY","say":"BRAND_NEW_KIND"}""").get("say").asString)
    }

    @Test
    fun `deleted range becomes a pair`() {
        val m = convert("""{"ts":"1","type":"SAY","conversationHistoryDeletedRange":{"startIndex":2,"endIndex":5}}""")
        assertEquals("[2,5]", m.get("conversationHistoryDeletedRange").toString())
    }

    @Test
    fun `enums sent as numbers convert like their names`() {
        assertEquals("text", convert("""{"ts":"1","type":1,"say":4}""").get("say").asString)
        assertEquals("command", convert("""{"ts":"1","type":"SAY","say":"10"}""").get("say").asString)
        assertEquals("tool", convert("""{"ts":"1","type":1,"say":12}""").get("say").asString)
        val ask = convert("""{"ts":"1","type":0,"ask":5}""")
        assertEquals("ask", ask.get("type").asString)
        assertEquals("tool", ask.get("ask").asString)
    }
}
