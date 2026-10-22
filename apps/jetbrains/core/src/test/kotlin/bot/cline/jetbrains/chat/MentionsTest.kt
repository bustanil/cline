package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {
    @Test
    fun `finds the mention being typed`() {
        assertEquals(MentionToken(0, ""), Mentions.tokenAt("@", 1))
        assertEquals(MentionToken(5, "src/a"), Mentions.tokenAt("Look @src/a", 11))
        assertEquals(MentionToken(5, "Ch"), Mentions.tokenAt("Fix\n @Ch", 8))
        assertEquals(MentionToken(0, "a"), Mentions.tokenAt("@a more text", 2))
    }

    @Test
    fun `no mention in an email address a finished mention or a plain word`() {
        assertNull(Mentions.tokenAt("mail me at a@b.com", 18))
        assertNull(Mentions.tokenAt("@a.kt done", 10))
        assertNull(Mentions.tokenAt("nothing here", 12))
        assertNull(Mentions.tokenAt("", 0))
    }

    @Test
    fun `accepting replaces the typed mention and keeps the rest`() {
        val text = "Look at @src/a and then"
        val token = Mentions.tokenAt(text, 14)!!
        assertEquals(MentionToken(8, "src/a"), token)
        val (result, caret) = Mentions.accept(text, 14, token, MentionMatch("src/app.kt", false, "app.kt"))
        assertEquals("Look at @/src/app.kt and then", result)
        assertEquals("Look at @/src/app.kt".length, caret)
        // At the end of the text a space is added so typing can go on.
        val (atEnd, endCaret) = Mentions.accept("@a", 2, MentionToken(0, "a"), MentionMatch("a.kt", false, null))
        assertEquals("@/a.kt ", atEnd)
        assertEquals(7, endCaret)
    }

    @Test
    fun `a folder mention ends with a slash and keeps typing inside it`() {
        val (result, caret) = Mentions.accept("@sr", 3, MentionToken(0, "sr"), MentionMatch("src", true, "src"))
        assertEquals("@/src/", result)
        assertEquals(6, caret)
        assertEquals("@/src/", MentionMatch("src/", true, null).text())
        assertEquals("@/a.kt", MentionMatch("/a.kt", false, null).text())
    }

    @Test
    fun `search sends the query and limit and parses files and folders`() {
        val core = object : bot.cline.jetbrains.bridge.CoreApi by FakeCore() {
            var seen = ""
            override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
                seen = "$service/$method $messageJson"
                return CompletableFuture.completedFuture("""{"results":[{"path":"src/a.kt","type":"file","label":"a.kt"},{"path":"src","type":"folder"},{"type":"file"}]}""")
            }
        }
        val results = Mentions.search(core, "a", 5).get()
        assertEquals(listOf(MentionMatch("src/a.kt", false, "a.kt"), MentionMatch("src", true, null)), results.matches)
        assertEquals("cline.FileService/searchFiles", core.seen.substringBefore(' '))
        val body = JsonParser.parseString(core.seen.substringAfter(' ')).asJsonObject
        assertEquals("a", body.get("query").asString)
        assertEquals(5, body.get("limit").asInt)
    }

    @Test
    fun `a search that failed carries the reason`() {
        val core = object : bot.cline.jetbrains.bridge.CoreApi by FakeCore() {
            override fun call(service: String, method: String, messageJson: String) =
                CompletableFuture.completedFuture("""{"errorReason":"ripgrep_spawn_failed","errorMessage":"rg missing"}""")
        }
        val results = Mentions.search(core, "x").get()
        assertEquals(emptyList(), results.matches)
        assertEquals("ripgrep_spawn_failed", results.errorReason)
        assertEquals("rg missing", results.errorMessage)
    }
}
