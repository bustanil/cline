package bot.cline.jetbrains.chat

import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangedFilesTest {
    @Test
    fun `parses status and counts and fills in proto defaults`() {
        val files = ChangedFile.parseList(
            """{"files":[
              {"path":"/ws/a.kt","relativePath":"a.kt","addedLines":3,"removedLines":1},
              {"path":"/ws/b.kt","relativePath":"b.kt","status":"CHANGED_FILE_ADDED","addedLines":10},
              {"path":"/ws/c.kt","status":"CHANGED_FILE_DELETED","removedLines":4}
            ]}""",
        )
        assertEquals(
            listOf(
                ChangedFile("/ws/a.kt", "a.kt", ChangedFile.Status.MODIFIED, 3, 1),
                ChangedFile("/ws/b.kt", "b.kt", ChangedFile.Status.ADDED, 10, 0),
                ChangedFile("/ws/c.kt", "/ws/c.kt", ChangedFile.Status.DELETED, 0, 4),
            ),
            files,
        )
        assertEquals(listOf('M', 'A', 'D'), files.map { it.status.letter })
    }

    @Test
    fun `an empty response is an empty list`() {
        assertEquals(emptyList(), ChangedFile.parseList("{}"))
    }

    @Test
    fun `the store asks the checkpoints service`() {
        val core = object : bot.cline.jetbrains.bridge.CoreApi by FakeCore() {
            val seen = mutableListOf<Triple<String, String, String>>()
            override fun call(service: String, method: String, messageJson: String): CompletableFuture<String> {
                seen += Triple(service, method, messageJson)
                return CompletableFuture.completedFuture("""{"files":[{"path":"/ws/a.kt","relativePath":"a.kt"}]}""")
            }
        }
        val store = ChatStore(core)
        assertEquals(listOf("a.kt"), store.changedFiles().get().map { it.relativePath })
        store.viewAllChanges().get()
        store.viewFileChanges("/ws/a.kt").get()
        store.revertFile("/ws/a.kt").get()
        assertEquals(
            listOf("checkpointLatestChangedFiles", "checkpointViewLatestChanges", "checkpointViewFileChanges", "checkpointRevertFile"),
            core.seen.map { it.second },
        )
        assertEquals("cline.CheckpointsService", core.seen.map { it.first }.distinct().single())
        assertEquals("/ws/a.kt", JsonParser.parseString(core.seen[3].third).asJsonObject.get("value").asString)
    }
}
