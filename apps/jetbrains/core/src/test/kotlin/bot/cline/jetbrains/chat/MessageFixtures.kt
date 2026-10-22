package bot.cline.jetbrains.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun say(ts: Long, kind: String, text: String = "") = msg(ts, "say", kind, text)

internal fun ask(ts: Long, kind: String, text: String = "") = msg(ts, "ask", kind, text)

internal fun msg(ts: Long, type: String, kind: String, text: String) =
    ChatMessage(JsonObject().apply { addProperty("ts", ts); addProperty("type", type); addProperty(type, kind); addProperty("text", text) })

internal fun tool(ts: Long, tool: String, path: String = "a.kt", extra: String = "", type: String = "say") =
    msg(ts, type, "tool", """{"tool":"$tool","path":"$path"$extra}""")

internal fun ChatMessage.json(): JsonObject = JsonParser.parseString(text()).asJsonObject
