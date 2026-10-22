# JetBrains native plugin: handover notes

Notes for building a Cline plugin for JetBrains IDEs with a **native UI** (Swing / Kotlin UI DSL, no JCEF webview). The plugin reuses the open-source agent core, `cline-core`, unchanged, and talks to it over the same protocol the closed-source official plugin uses.

Facts below come from this repository as of commit `5dcfddc` (fork `bustanil/cline`, `main`). Points marked **Verify** were not confirmed by running anything.

## Decisions already made

| Decision | Why |
|---|---|
| Native UI, not the webview | The webview depends on 93 `--vscode-*` CSS variables that JetBrains does not supply, which is why the official plugin looks off. Native components follow the IDE theme for free. |
| Reuse `cline-core` as is | All agent logic, storage (`~/.cline/data`), providers and tools already run there. The plugin is only the IDE side and the UI. |
| Not ACP | Cline's ACP agent (`apps/cli/src/acp`) sends no diffs, no file locations, and refuses embedded snippets (`embeddedContext: false`), which are the features this plugin exists for. |
| Commits go to `main` of the fork, no PRs | Per the owner. |

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
2. **Minimal chat:** `getLatestState` + `subscribeToState` + `subscribeToPartialMessage` + the merging logic; send with `newTask`; show text messages. Implement `getWorkspacePaths`, `showMessage`, `getHostVersion` and the telemetry calls.
3. **Approvals:** `buttonConfig.ts` → two buttons + `askResponse`; Cancel button.
4. **Navigation:** `showTextDocument` with selection; links in chat.
5. **Snippets:** editor actions + intention.
6. **Changes:** changed-files list + `openMultiFileDiff`.
7. Rendering rows properly, history, Plan/Act, model picker.

## Known gaps and things to check

- **Verify:** the entry file name and folder layout produced by `package-standalone.mjs`, and how the official plugin bundles Node, before designing your own packaging.
- **Verify:** proto3 JSON written by `protobuf-java` decodes in the core for every request you send. The core decodes with ts-proto `fromJSON` (`src/standalone/core-connection-dispatcher.ts`), and both follow the same JSON mapping.
- The changed-files list only refreshes when you call it. The core has no live update during a turn yet.
- After a per-file revert the agent is not told; it sees the restored file on its next read.
- In this repo's unit tests, `cline-rules.test.ts` and `toggleClineRule.test.ts` each have one test that fails when run as root. It is unrelated to this work.
