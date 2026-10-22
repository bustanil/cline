# JetBrains native plugin: handover notes

Notes for building a Cline plugin for JetBrains IDEs with a **native UI** (Swing / Kotlin UI DSL, no JCEF webview). The plugin reuses the open-source agent core, `cline-core`, unchanged, and talks to it over the same protocol the closed-source official plugin uses.

Facts below come from this repository as of commit `5dcfddc` (fork `bustanil/cline`, `main`). Points marked **Verify** were not confirmed by running anything.

## Decisions already made

| Decision | Why |
|---|---|
| Fully native UI, chat included, not the webview | The webview depends on 93 `--vscode-*` CSS variables that JetBrains does not supply, which is why the official plugin looks off. Native components follow the IDE theme for free. The webview chat (about 16,000 lines) is the reference implementation, so porting it is effort rather than risk. |
| Unknown message kinds are drawn as plain text | Keeps the first version small (about a dozen kinds drawn properly) and means a kind added upstream degrades to text instead of breaking the chat. Port kinds one at a time when they matter. |
| Reuse `cline-core` as is | All agent logic, storage (`~/.cline/data`), providers and tools already run there. The plugin is only the IDE side and the UI. |
| Not ACP | Cline's ACP agent (`apps/cli/src/acp`) sends no diffs, no file locations, and refuses embedded snippets (`embeddedContext: false`), which are the features this plugin exists for. |
| Commits go to `main` of the fork, no PRs | Per the owner. |

## Where the plugin stands

The plugin lives in `apps/jetbrains` (Gradle, Kotlin, JVM 21). All seven steps under [Suggested order](#suggested-order) work. They are tested against the real core, not only fakes.

| Module | Holds |
|---|---|
| `:core` | Plain Kotlin, no IDE classes. The gRPC server the core connects to, request routing, the process supervisor (`CoreHost`), the chat model (`ChatStore`, `ReplicaState`, `Transcript`, `RowRenderer`), button config, file links, editor commands, changed files, history, models and `@` mentions. |
| `:plugin` | IntelliJ Platform code. `IntelliJBridge` implements `IdeBridge`. The tool window, rows, panels, editor actions, the "Fix with Cline" intention and settings. |

Build and test:

```bash
# Once: build the core (needs Bun 1.4.2)
cd apps/vscode && bun install && bun run build:sdk && bun run protos && bun esbuild.mjs --standalone && node scripts/package-standalone.mjs
cd apps/jetbrains
./gradlew :core:test :plugin:test                      # unit and IDE-fixture tests
CLINE_CORE_JS=$PWD/../vscode/dist-standalone/cline-core.js ./gradlew :core:test :plugin:test   # also runs against the real core
./gradlew :plugin:buildPlugin                          # plugin/build/distributions/plugin.zip, with the core inside if it was built
```

What each step became:

| Step | Where |
|---|---|
| 1 Connection | `CoreConnectionServer`, `CoreHost`, `CoreConnection`, `EnvService`, `WorkspaceService`, `WindowService`, `DiffService` |
| 2 Minimal chat | `ChatStore`, `ReplicaState` (port of `messageReducer.ts`), `ProtoMessageConverter` |
| 3 Approvals | `ButtonConfigs` (port of `buttonConfig.ts`), `ChatStore.perform`, `ChatStore.send` |
| 4 Navigation | `FileLinks`, `MarkdownHtml`, `IntelliJBridge.showTextDocument` |
| 5 Snippets | `EditorCommands`, `EditorContextBuilder`, `ClineEditorActions`, `FixWithClineIntention` |
| 6 Changes | `ChangedFile`, `ChangedFilesPanel`, `IntelliJBridge.openMultiFileDiff` |
| 7 Rows, history, Plan/Act, models | `Transcript`, `RowRenderer`, `HtmlRow`, `HistoryPanel`, `ModelPanel`, toolbar in `ChatPanel`, `MentionController` |

Facts this work established, which the sections below did not know:

- **No gRPC code generator.** The grpc-java generator and the repo's own `protoc` both ship an x86_64 binary for macOS arm64 and need Rosetta. The build generates message classes only. `HostService` and `CoreConnectionService` define services by hand.
- **The core needs `extension/package.json`** next to `cline-core.js`. `package-standalone.mjs` does not create it. The plugin build copies `apps/vscode/package.json` there.
- **The standalone build does not choose `better-sqlite3` for you.** It ships one per platform under `binaries/<os>-<arch>/node_modules` and leaves the choice to the installer. The plugin sets `NODE_PATH` to the right folder (`CoreLocator.environmentFor`). Those binaries are built for Node 22, so the plugin refuses to start the bundled core on another Node major and says so.
- **Partial messages use proto enum names** (`TOOL_SAY`), state snapshots use strings (`tool`). `ProtoMessageConverter` maps them. Proto3 JSON leaves out default values, so an absent `type` is `ASK` and an absent `say` is `task`.
- **A failed request shows as `ask/api_req_failed`**, and the webview hides that kind. The plugin shows it, because in this build it is the only place the reason appears.
- **Checkpoint reverts go back to the latest checkpoint**, not to "before the last edit". The revert dialog says so.
- **`commitModelSelection` also switches the provider** for that mode. `writeProviderConfig` stores the API key; `readProviderConfig` returns only its length.

Not done, and not verified:

- **Checked on Node 22.15.0:** with the official macOS arm64 build (checksum verified), the core bundled in the plugin starts, takes its `better-sqlite3` from `binaries/darwin-arm64` through `NODE_PATH`, and connects (`CoreLocatorTest`, run with `CLINE_NODE` set to that Node). The other platforms' binaries were not run. The remaining tests run on Node 24 against a core whose `better-sqlite3` resolves from the source checkout.
- **A task that finishes.** No model credential was available, so no test ran an agent turn to completion. Tool approvals, diffs and the changed-files list are tested with fake data and with the real core's empty answers, not with a real edit.
- **Not ported:** attaching images and files, editing an earlier message (`editMessageAndRegenerate`), browser sessions, MCP, subagents, worktrees, cloud sessions, rules and workflows screens, and a proper settings screen for providers beyond the key and model. These messages draw as plain text. The input has no image paste.
- **Runtime checks that need a person:** how the tool window looks in light and dark themes, the `@` popup position, and the diff window opened from the editor. The tests cover the logic and the component trees, not the pixels. `plugin.zip` was built but not loaded in a running IDE.
- **The two gaps below** (live changed-files list, the agent not told about a revert) still hold. Both were confirmed in `SdkController.ts`.

## Goals, in priority order

1. **Two-way IDE ↔ agent integration.**
   - Selected code goes to the agent with its file and lines.
   - Everything the agent mentions opens at the right line.
2. **Reviewing changes across many files:** a list of changed files, a real multi-file diff, revert per file.
3. **File links that always work:** absolute, `~/`, relative to any workspace root, with `:line` suffixes.
4. A native chat that looks like the rest of the IDE.

Steps 1–3 already added the core side of these. See [What steps 1–3 added](#what-steps-13-added-for-this-plugin).

## Architecture

```
┌──────────────── JetBrains plugin (Kotlin) ────────────────┐
│ Tool window (native Swing)   Editor actions, intentions   │
│        │                              │                   │
│        ▼                              ▼                   │
│  ChatStore (messages, state)  ◀──  CoreClient             │
│                                  (calls into the core)    │
│                                       │                   │
│  HostBridgeServer: one gRPC server on 127.0.0.1           │
│   ├─ grpc.health.v1.Health        (core waits for it)     │
│   ├─ host.CoreConnectionService   (stream carrying       │
│   │                                CoreClient's calls)    │
│   └─ host.Workspace/Window/Diff/EnvService                │
│                                   (core calls the IDE)    │
└───────────────────────────┬───────────────────────────────┘
          starts the process │ ▲ core connects back with token
                             ▼ │
                 node cline-core.js   (apps/vscode standalone build)
```

There is one socket. The plugin listens; `cline-core` connects to it. Two kinds of traffic share it:
- **Core → IDE:** normal gRPC calls to the four `host.*` services. Examples: "show this file", "show this message", "open this diff".
- **Plugin → core:** every call the webview would make (`cline.TaskService.newTask`, `cline.StateService.subscribeToState`, …), carried inside the core's long-lived `CoreConnectionService.connect` stream.

## 1. Building and starting `cline-core`

From `apps/vscode` (run `bun install` and `bun run build:sdk` at the repo root first):

```bash
bun run compile-standalone          # check-types + lint + esbuild --standalone → dist-standalone/cline-core.js
node scripts/package-standalone.mjs # installs runtime deps into dist-standalone, zips it
```

- `package-standalone.mjs` targets **Node 22.15.0** (`TARGET_NODE_VERSION`). Its native modules are built for that ABI, so ship and run that Node version.
- Platforms: `win-x64`, `darwin-x64`, `darwin-arm64`, `linux-x64`, `linux-aarch64`.

**Start it** as `<node> <dist-standalone>/cline-core.js`. The core `chdir`s into its own folder, so the working directory doesn't matter.

| Setting | Value |
|---|---|
| `CLINE_CORE_CONNECTION_TOKEN` (env) | A fresh random secret per spawn. The core reads it, deletes it from its environment (so child processes never see it), and sends it with every call to the plugin. |
| `CLINE_CORE_INSTANCE_ID` (env) | Unique per spawn. Required when the token is set. |
| `HOST_BRIDGE_ADDRESS` (env) or `--host-bridge-port <n>` | Where the plugin's gRPC server listens, e.g. `127.0.0.1:41234`. Pick a free port. |
| `--config <dir>` (optional) | Data folder. Defaults to the shared `~/.cline`, so `cline auth` from the CLI also signs in the plugin. |

**Startup sequence** (`src/standalone/cline-core.ts`):
1. The core polls `grpc.health.v1.Health/Check` on the host bridge until it returns `SERVING`. It gives up after 60 s. **Serve the health service, or the core never starts.**
2. It registers itself in `~/.cline/data/locks.db`.
3. It opens `host.CoreConnectionService/connect` and sends `hello { token, instance_id }`.
4. The plugin checks the token and replies `ready {}`. From then on the stream carries the plugin's calls (section 2).

If the stream fails, the core shuts itself down. Restart it with growing delays (Continue's `CoreMessengerManager.kt` doubles from 1 s), and start the core again whenever the stream drops.

## 2. Transport details

**Authentication.** Every call from the core carries the gRPC metadata header `cline-hostbridge-token: <token>`. Reject calls without it (`src/hosts/external/host-bridge-auth.ts`).

**Calling the core over the connect stream** (`proto/host/core_connection.proto`):

```
plugin → core   request  { request_id, service: "cline.TaskService", method: "newTask",
                           message_json: "{\"text\":\"…\"}", is_streaming: false }
core → plugin   response { request_id, message_json_chunk: <bytes>, message_json_complete: false }
core → plugin   response { request_id, message_json_chunk: <bytes>, message_json_complete: true, completed: true }
plugin → core   cancel   { request_id }            // ends a subscription
```

- `service` is the fully qualified name: `cline.<Service>`. Methods are camelCase, as in the `.proto` files.
- `message_json` is **proto3 JSON**: lowerCamelCase field names, enums as their string names, default values left out. `protobuf-java`'s `JsonFormat.printer()` produces this by default.
- Responses arrive as chunks. Join every `message_json_chunk` up to `message_json_complete`, then parse. Also accept a whole `message_json`; the protocol allows both.
- `error` set means the call failed.
- A streaming call (`is_streaming: true`) sends many payloads and ends with `completed: true`. The core's list of streaming methods is `responseStreamingMethods` in the generated `src/generated/hosts/vscode/protobus-services.ts`.

**Kotlin code generation.** Every `.proto` already sets `java_package` (`bot.cline.proto` for `cline/*`, `bot.cline.host.proto` for `host/*`). Generate with `protobuf-java` plus `grpc-java` or `grpc-kotlin`, using the Gradle protobuf plugin pointed at `apps/vscode/proto`. You need server stubs for `host/*` and only message classes for `cline/*`; calls to the core use the JSON envelope above, not gRPC stubs.

## 3. IDE services the core calls

How often the core calls each one (`HostProvider.*` uses in `src/`, excluding tests) shows where to spend effort:

| Call | Uses | Native implementation |
|---|---|---|
| `window.showMessage` | 52 | `Notifications` balloon. Return the clicked option for messages with `options.items`. |
| `workspace.getWorkspacePaths` | 17 | Content roots of the project (`ProjectRootManager`). |
| `env.getHostVersion`, `getTelemetrySettings`, `subscribeToTelemetrySettings` | 7, 7, 4 | IDE name/version; telemetry setting (keep the subscription open). |
| `window.showTextDocument` | 4 | `OpenFileDescriptor(project, vfile, line, 0).navigate(true)` + select `options.selection` (see below). |
| `diff.openMultiFileDiff` / `closeAllDiffs` | 2 / 1 | IntelliJ diff window with file navigation (see below). |
| everything else | ~1 each | `getActiveEditor`, `getOpenTabs`, `getDiagnostics`, `executeCommandInTerminal`, `clipboard*`, `openExternal`, … |

Not used by the core in this build: `diff.openDiff`, `replaceText`, `scrollDiff`, `truncateDocument`, `getDocumentText`, `saveDocument` (VS Code's own `openDiff` just throws). Return an "unsupported" error for those.

**Threading:** never block the UI thread inside a gRPC handler. Use `invokeLater` for UI work and `WriteCommandAction` for document edits. Continue capped its IDE handlers at 4 parallel workers after the IDE froze (their issue #4098).

## 4. The chat UI: what the webview does that you must reproduce

**Chat state** (`webview-ui/src/context/ExtensionStateContext.tsx`):

- On open: `cline.StateService.getLatestState`, then subscribe to `cline.StateService.subscribeToState`. Each payload is `State { state_json }`, the **whole** `ExtensionState` (about 80 fields) as JSON. You'll mostly need these fields:
  - `clineMessages` (the conversation)
  - `mode`
  - `apiConfiguration` and `autoApprovalSettings`
  - `taskHistory`
  - `turnState`
- Also subscribe to `cline.UiService.subscribeToPartialMessage`, which streams single `ClineMessage` updates while a reply is being written.
- **Merging:** don't replace the message list with each snapshot. Port `webview-ui/src/components/chat/chat-view/messageReducer.ts`; its tests are the spec:
  - messages are identified by `ts`;
  - a higher `seq` is a newer copy of the same message;
  - a newer `epoch` replaces everything (new task or reload);
  - older snapshots are ignored.
  - Take `turnState` from the merged result. A late snapshot must not hide the Cancel button.

**Messages** (`src/shared/ExtensionMessage.ts`): each `ClineMessage` is `type: "say"` or `"ask"` with a `say` or `ask` kind.
- `"ask"` messages wait for the user.
- `text` often holds JSON. For example, `say/ask "tool"` holds a `ClineSayTool`: `tool`, `path`, `diff`, `content`, `readLineStart`/`readLineEnd`, …

**Which kinds the current core sends.** The type definitions list 54 kinds, but many are leftovers from Cline's older engine. A text search of `src/sdk` and the controller finds about 30. A kind sent through a variable could be missing, so treat the list as approximate.

| Group | Kinds | First version |
|---|---|---|
| Questions (`ask`) | `tool`, `command`, `followup`, `completion_result`, `api_req_failed` | Draw properly, with the buttons from `buttonConfig.ts` |
| | `resume_task`, `resume_completed_task`, `use_mcp_server`, `use_subagents` | Plain text + the buttons from `buttonConfig.ts` |
| Messages (`say`) | `text`, `reasoning`, `task`, `user_feedback`, `tool`, `command`, `error`, `completion_result`, `api_req_started` (cost and progress) | Draw properly |
| | `info`, `plan_completion_result`, `checkpoint_created`, `compaction`, `hook_status`, `task_progress`, `mcp_server_request_started`, `mcp_server_response`, `use_mcp_server`, `subagent`, `subagent_usage`, `use_subagents` | Plain text |
| Tool types inside `tool` (`ClineSayTool.tool`) | `editedExistingFile`, `newFileCreated`, `fileDeleted`, `readFile` | Draw properly: path as a link (with lines for reads), diff for edits |
| | `listFilesTopLevel`, `listFilesRecursive`, `listCodeDefinitionNames`, `searchFiles`, `webFetch`, `webSearch`, `summarizeTask`, `useSkill` | One line: tool + path or query |

**Fallback rule.** Any `say`/`ask` kind or tool type the plugin doesn't know is drawn as plain text: the kind as a small label, then `text` (pretty-printed if it is JSON). For an unknown `ask`, still show the buttons `buttonConfig.ts` gives it. Never drop a message or throw.

Port these before drawing rows:

| Source (webview-ui/src unless noted) | Purpose |
|---|---|
| `src/shared/combineApiRequests.ts`, `combineCommandSequences.ts`, `combineHookSequences.ts` | Merge request start/finish, command + output, and hook events into single rows |
| `components/chat/chat-view/utils/messageUtils.ts` | Grouping of low-stakes tool calls ("Cline read 3 files") |
| `components/chat/chat-view/shared/buttonConfig.ts` | **What the two action buttons say and do for each `ask` kind.** One source of truth for approvals |
| `components/chat/chat-view/hooks/useMessageHandlers.ts` | What each button sends |
| `components/chat/ChatRow.tsx` | How each `say`/`ask` kind renders |

**Calls the chat needs** (`cline.TaskService` unless noted):

| Action | Call |
|---|---|
| Start a task | `newTask { text, images, files }` |
| Approve / reject / reply | `askResponse { responseType: "yesButtonClicked" \| "noButtonClicked" \| "messageResponse", text }` |
| Cancel | `cancelTask` |
| New chat | `clearTask` |
| History | `getTaskHistory`, `showTaskWithId`, `deleteTasksWithIds` |
| Edit an earlier message | `editMessageAndRegenerate` |
| Plan/Act | `cline.StateService.togglePlanActModeProto` |
| File and `@` mentions | `cline.FileService.searchFiles` |
| Snippet from the editor | subscribe to `cline.UiService.subscribeToAddToInput` and insert the text into the input |
| Models | `cline.ModelsService.readProviderConfig`, `resolveProviderModels`, `commitModelSelection`, `writeProviderConfig` |

Webview-only calls you don't need: `UiService.initializeWebview` and `getWebviewHtml`. You also don't need the `subscribeTo…ButtonClicked` family; it exists so the VS Code title bar can talk to the webview, and native toolbar actions update your UI directly.

**For a first version, leave out:** settings screens (sign in with `cline auth` from the CLI instead, since storage is shared), MCP marketplace, worktrees, cloud sessions, browser tool rendering, subagents. Show their messages as plain text.

**Native components:**
- **Markdown:** convert with `commonmark-java` and show in a `JBHtmlPane`.
- **Code blocks:** a read-only `EditorTextField`/`EditorEx` so they get real syntax highlighting.
- **Message list:** a `JBList` or a vertical panel inside a `JBScrollPane`.
- **Input:** a multi-line `EditorTextField`, with `@`-completion from `searchFiles`.

## What steps 1–3 added for this plugin

All three are in the core and work in VS Code already. The plugin's part:

| Step | Core addition | Plugin's job |
|---|---|---|
| 1. File links at the right line (`6a11b71`) | `FileService.resolveFileReference` and `openFileLocation`. The core resolves `path`, `~/path`, `path:12`, `path:12-20`, `path#L12-L20` against every workspace root. `host.WindowService.showTextDocument` gets `options.selection { start_line, end_line? }` (1-based). | In the chat, make inline code that `resolveFileReference` resolves into links that call `openFileLocation`. In `showTextDocument`, navigate to `start_line` and select through `end_line`. |
| 2. Snippets with lines (`872c931`) | `CommandContext` gains `start_line`/`end_line` (1-based). Prompts read `@/src/app.ts (lines 12-20)`. | Editor actions **Add to Chat / Fix / Explain / Improve** call `cline.CommandsService.addToCline` / `fixWithCline` / `explainWithCline` / `improveWithCline`. Each sends `file_path`, `selected_text`, `language`, diagnostics in the selection, and the line range. Don't count the last line if the selection ends at column 0 of it. Add an intention action "Fix with Cline" (Alt+Enter) on errors. |
| 3. Per-file review (`5dcfddc`) | `CheckpointsService.checkpointLatestChangedFiles` (status + line counts), `checkpointViewFileChanges(path)`, `checkpointRevertFile(path)`; "View Changes" stays `checkpointViewLatestChanges`. | After a turn completes, show the changed files (`M`/`A`/`D`, +/−) with open / diff / revert (ask for confirmation). Implement `host.DiffService.openMultiFileDiff` as IntelliJ's diff window with a file list: a `SimpleDiffRequestChain` of `SimpleDiffRequest`s. Use `DiffContentFactory.create(project, vfile)` for the right side when its content matches the file on disk, so you can edit it and revert individual changes. Otherwise use a read-only text document. |

## Suggested order

1. **Connection:** Gradle IntelliJ Platform project; health service plus `CoreConnectionService` with the token check; start `cline-core` and reach the `ready` reply. Test against a fake core that only sends `hello`, then the real one.
2. **Minimal chat:** `getLatestState` + `subscribeToState` + `subscribeToPartialMessage` + the merging logic; send with `newTask`; draw every message with the plain-text fallback first, then the first-version kinds properly. Implement `getWorkspacePaths`, `showMessage`, `getHostVersion` and the telemetry calls.
3. **Approvals:** `buttonConfig.ts` → two buttons + `askResponse`; Cancel button.
4. **Navigation:** `showTextDocument` with selection; links in chat.
5. **Snippets:** editor actions + intention.
6. **Changes:** changed-files list + `openMultiFileDiff`.
7. Rendering rows properly, history, Plan/Act, model picker.

## Known gaps and things to check

- **Checked (apps/jetbrains):** `package-standalone.mjs` leaves `dist-standalone/cline-core.js` plus `node_modules`, `proto`, `plugin-runtime` and `standalone.zip`. It does not create `dist-standalone/extension/package.json`, which the core reads at startup (`INSTALL_DIR/extension/package.json`; `INSTALL_DIR` defaults to the folder of `cline-core.js`). Copy `apps/vscode/package.json` there, or the core exits with ENOENT. How the official plugin bundles Node is still unchecked.
- **Checked:** the real core reaches `hello` and `ready` against `apps/jetbrains` (`RealCoreSmokeTest`, run with `CLINE_CORE_JS=<path to cline-core.js>`). Before `hello`, it calls `host.EnvService/getHostVersion`, so serve `EnvService` along with the health service. The test ran on Node 24, not the bundled 22.15.0.
- **Checked:** with `HostBridge` workspace and window services served, `newTask` on the real core streams a transcript to the plugin (`task`, `api_req_started`, then `ask/api_req_failed` when no provider credential is set; `turnState.phase` is `error`). Partial messages arrive in proto3 JSON with enum names (`TOOL_SAY`); `ProtoMessageConverter` turns them into the strings state snapshots use.
- **Apple Silicon build tooling:** `bun run protos` and the grpc-java code generator both need Rosetta, because their macOS arm64 binaries are x86_64. Without Rosetta, copy a native protoc (for example the one Gradle downloads) over `node_modules/.../grpc-tools/bin/protoc`, and skip the Rosetta check in `scripts/build-proto.mjs` for that run. `apps/jetbrains` defines its gRPC services by hand for the same reason.
- **Verify:** proto3 JSON written by `protobuf-java` decodes in the core for every request you send. The core decodes with ts-proto `fromJSON` (`src/standalone/core-connection-dispatcher.ts`), and both follow the same JSON mapping.
- The changed-files list only refreshes when you call it. The core has no live update during a turn yet.
- After a per-file revert the agent is not told; it sees the restored file on its next read.
- In this repo's unit tests, `cline-rules.test.ts` and `toggleClineRule.test.ts` each have one test that fails when run as root. It is unrelated to this work.
