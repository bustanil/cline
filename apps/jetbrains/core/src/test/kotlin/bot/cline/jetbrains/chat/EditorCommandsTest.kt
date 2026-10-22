package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorCommandsTest {
    @Test
    fun `selected lines are 1 based and a selection ending at column 0 stops a line earlier`() {
        assertEquals(12 to 20, EditorCommands.selectedLines(11, 4, 19, 10))
        assertEquals(12 to 19, EditorCommands.selectedLines(11, 0, 19, 0))
        assertEquals(5 to 5, EditorCommands.selectedLines(4, 3, 4, 9))
        assertEquals(5 to 5, EditorCommands.selectedLines(4, 3, 4, 0))
    }

    @Test
    fun `a diagnostic touches the selection when its lines overlap`() {
        val d = EditorDiagnostic("x", 5, 0, 7, 3, EditorDiagnostic.Severity.ERROR)
        assertTrue(EditorCommands.touches(d, 7, 9))
        assertTrue(EditorCommands.touches(d, 0, 5))
        assertFalse(EditorCommands.touches(d, 8, 9))
        assertFalse(EditorCommands.touches(d, 0, 4))
    }

    @Test
    fun `the context json uses the CommandContext field names and proto enum names`() {
        val json = JsonParser.parseString(
            EditorCommands.contextJson(
                EditorContext(
                    "/ws/A.kt", "val x = 1", "kotlin", 12, 20,
                    listOf(EditorDiagnostic("Type mismatch", 12, 4, 12, 9, EditorDiagnostic.Severity.WARNING, "kotlin")),
                ),
            ),
        ).asJsonObject
        assertEquals("/ws/A.kt", json.get("filePath").asString)
        assertEquals("val x = 1", json.get("selectedText").asString)
        assertEquals("kotlin", json.get("language").asString)
        assertEquals(12, json.get("startLine").asInt)
        assertEquals(20, json.get("endLine").asInt)
        val d = json.getAsJsonArray("diagnostics")[0].asJsonObject
        assertEquals("DIAGNOSTIC_WARNING", d.get("severity").asString)
        assertEquals(4, d.getAsJsonObject("range").getAsJsonObject("start").get("character").asInt)
        assertEquals("kotlin", d.get("source").asString)
    }

    @Test
    fun `lines are left out when unknown`() {
        val json = JsonParser.parseString(EditorCommands.contextJson(EditorContext("/a", "t", "text", null, null, emptyList()))).asJsonObject
        assertFalse(json.has("startLine") || json.has("endLine"))
    }

    @Test
    fun `each command calls its CommandsService method`() {
        val core = FakeCore()
        val store = ChatStore(core)
        for (command in EditorCommand.values()) store.runEditorCommand(command, EditorContext("/a", "t", "text", 1, 1, emptyList())).get()
        assertEquals(listOf("addToCline", "fixWithCline", "explainWithCline", "improveWithCline"), core.calls.map { it.second })
        assertTrue(core.calls.all { it.first == "cline.CommandsService" })
    }

    @Test
    fun `text the core adds to the input reaches listeners`() {
        val core = FakeCore()
        val store = ChatStore(core)
        val seen = mutableListOf<String>()
        store.addAddToInputListener { seen += it }
        store.start().get()
        core.streams.getValue("cline.UiService/subscribeToAddToInput")("""{"value":"@/src/a.kt (lines 1-2)\n```\nx\n```"}""")
        core.streams.getValue("cline.UiService/subscribeToAddToInput")("{}")
        assertEquals(listOf("@/src/a.kt (lines 1-2)\n```\nx\n```"), seen)
    }
}
