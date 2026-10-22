package bot.cline.jetbrains.chat

import com.google.gson.JsonObject

/** One thing the agent can be allowed to do without asking. */
data class ApprovalAction(
    val id: String,
    val label: String,
    /** The name in the "Auto-approve: Read, Edit, …" line; null for an extra that only widens another action. */
    val shortName: String?,
    /** The action this one widens, such as reading files outside the workspace. */
    val parent: String? = null,
)

/** What the agent may do without asking, as the core stores it. */
data class AutoApprovalSettings(val version: Int, val actions: Map<String, Boolean>, val enableNotifications: Boolean) {
    fun isOn(id: String): Boolean = actions[id] == true

    /** A copy with [id] switched. The core compares versions, so each change carries the next one. */
    fun with(id: String, on: Boolean) = copy(version = version + 1, actions = actions + (id to on))
}

/** The actions, in the order the VS Code client lists them, and the text that summarizes them. */
object AutoApproval {
    val ACTIONS: List<ApprovalAction> = listOf(
        ApprovalAction("readFiles", "Read files", "Read"),
        ApprovalAction("readFilesExternally", "Read files outside the workspace", null, parent = "readFiles"),
        ApprovalAction("editFiles", "Edit files", "Edit"),
        ApprovalAction("editFilesExternally", "Edit files outside the workspace", null, parent = "editFiles"),
        ApprovalAction("executeSafeCommands", "Run safe commands", "Commands"),
        ApprovalAction("executeAllCommands", "Run all commands", null, parent = "executeSafeCommands"),
        ApprovalAction("useBrowser", "Fetch web content", "Web Fetch"),
        ApprovalAction("useMcp", "Use MCP servers", "MCP"),
    )

    /** Reads `autoApprovalSettings` from the extension state; null when the state has none. */
    fun parse(state: JsonObject): AutoApprovalSettings? {
        val o = state.getAsJsonObject("autoApprovalSettings") ?: return null
        val actions = o.getAsJsonObject("actions")?.entrySet()?.associate { (k, v) -> k to (v.isJsonPrimitive && v.asBoolean) }.orEmpty()
        return AutoApprovalSettings(o.get("version")?.asInt ?: 1, actions, o.get("enableNotifications")?.asBoolean == true)
    }

    /** `Read, Edit, Web Fetch, MCP`, or `None`. */
    fun summary(settings: AutoApprovalSettings?): String {
        val names = ACTIONS.filter { it.shortName != null && settings?.isOn(it.id) == true }.map { it.shortName!! }
        return if (names.isEmpty()) "None" else names.joinToString(", ")
    }

    /** The `updateAutoApprovalSettings` request. Every action is sent, since the core replaces the set. */
    fun requestJson(settings: AutoApprovalSettings): String = JsonObject().apply {
        addProperty("version", settings.version)
        add("actions", JsonObject().also { a -> ACTIONS.forEach { a.addProperty(it.id, settings.isOn(it.id)) } })
        addProperty("enableNotifications", settings.enableNotifications)
    }.toString()
}
