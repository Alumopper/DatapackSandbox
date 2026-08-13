# Serve JSONL Protocol

## When to use this page

Start `serve` when an editor, notebook kernel, language tool, or another process needs one long-lived sandbox with structured requests, completion, rendering, event paging, checkpoints, and cancellable execution. Use `run` or `check` for ordinary one-shot shell automation; use the Core API when the integration can run in the same JVM.

## Prerequisites

Build or obtain the standalone CLI JAR, then start it as a child process:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar serve --protocol jsonl
```

The transport is UTF-8 stdin/stdout, one complete JSON object per line. Do not pretty-print requests across lines. Human-readable logs are not mixed into stdout, so reserve stdout exclusively for the protocol and capture stderr separately.

## Envelope and startup

The server immediately writes an unsolicited hello response with `id: null`:

```json
{"id":null,"ok":true,"result":{"protocol":"dps-jsonl","defaultVersion":"26.2","capabilities":{"render":true,"renderMimeType":"image/png","checkpoints":true,"functionSource":true,"interrupt":true,"eventTraces":true,"pagedEvents":true,"richOutput":true,"coverage":true,"commandDiagnostics":true},"versions":["1.20.4","26.2"]}}
```

The shown `versions` array is abbreviated. Wait for this line before enabling controls, verify `protocol`, retain `defaultVersion`/`versions`, and drive optional UI from `capabilities` rather than assuming all CLI versions expose the same surface.

Every request contains an application-chosen `id`, a `method`, and an optional object `params`:

```json
{"id":"req-1","method":"state","params":{}}
```

IDs may be strings or numbers and are echoed as JSON values. Use unique outstanding IDs so out-of-band `interrupt` responses and queued operations can be correlated reliably.

Success:

```json
{"id":"req-1","ok":true,"result":{"version":"26.2","gameTime":0,"entities":0,"players":0}}
```

Failure:

```json
{"id":"req-2","ok":false,"error":{"code":"COMMAND_ERROR","message":"...","version":"26.2","command":"...","location":{"file":"demo.mcfunction","line":2,"command":"..."}}}
```

Consumers should preserve unknown result/error fields for forward compatibility and should not parse `message` to recover a code.

## Minimal complete session

Send these lines after the startup hello:

```jsonl
{"id":"1","method":"createFunctionSandbox","params":{"version":"26.2","defaultPlayerName":"Alex","mcfunctionId":"demo:main","mcfunctionText":"scoreboard objectives add runs dummy\nscoreboard players add #serve runs 1\nsay hello"}}
{"id":"2","method":"runFunction","params":{"id":"demo:main"}}
{"id":"3","method":"outputs","params":{"from":0}}
{"id":"4","method":"coverage","params":{}}
```

The tracked `runFunction` result contains this operation's command count, outputs, traces, player-event traces, state-only snapshot diffs, and a compact current state. Keep that result even if a following query fails; it is already committed sandbox state.

## Create and reload a sandbox

`createSandbox`, `createFunctionSandbox`, and `open` are aliases. They replace the active sandbox and configuration.

| Parameter | Shape | Meaning |
| --- | --- | --- |
| `version` | string, defaults to server default | Minecraft profile id |
| `packs` | string or string[] | Datapack directory/ZIP paths |
| `functionSources` | array | `{id,text|content}` or `{id,path|file}`, optional `sourceName` |
| `mcfunction` | string path | One file-backed function |
| `mcfunctionText` | string | One in-memory function |
| `mcfunctionId` | string, default `sandbox:main` | Id for the single file/text source |
| `defaultPlayerName` | string or null | Optional initial player; omitted means no implicit player in `serve` |
| `unsupported` | `warn`, `ignore`, or `error` | Unsupported-feature policy |
| `limits` | object | `maxCommands`, `maxFunctionDepth`, `maxTicksPerRun`, `maxOutputEvents`, `maxSnapshotBytes` |

All host paths are normalized by the `serve` process. Relative paths therefore resolve from that process's working directory, not from the editor document unless both are the same.

Use `upsertFunctionSource` with `id` plus exactly one of `text` or `path` to replace/add a synthetic function while retaining world state. `reload` reconstructs resources from the saved configuration; `keepWorld` defaults to `true`. `resetWorld` reconstructs the configured resources over a fresh sparse world. Resource reload is not a transaction across external file changes, so save editor content first and handle a failed reload without discarding the last client-side view.

## Methods

### Execution methods

| Method and aliases | Key params | Notes |
| --- | --- | --- |
| `load` | — | Runs `#minecraft:load` |
| `tick`, `ticks` | `count`, default 1 | Advances the modeled lifecycle |
| `runFunction`, `function` | `id` | Runs a loaded function |
| `runCommand`, `command` | `command`, optional `file`, `line`, `allowFailure` | Executes one command |
| `runCommands`, `commands` | `commands`, optional `file`, `allowFailure` | Skips blank/comment lines; generated line numbers are one-based |
| `runManifest` | `path`, optional `strict` | Runs a manifest in the active sandbox and replaces it with the returned sandbox |
| `applyWorldFixture`, `world` | `world`/`fixture` object or `path`, optional `base` | Applies manifest-style world setup |
| `injectPlayerEvent`, `event` | `event`, or `player` + `type` + optional `id`/`detail` | Creates the player if needed, then dispatches the event |

Tracked methods return only events produced by that operation, plus `snapshotDiffs` and `state`. `allowFailure: true` suppresses a command exception for exploratory tooling but does not make the command successful; inspect the new trace.

### State and resource queries

| Method | Result |
| --- | --- |
| `snapshot` | Complete modeled snapshot object |
| `snapshotString` | Stable snapshot text in `{snapshot}` |
| `state` | Compact counts, version, checkpoints, and resource summary |
| `resources` | Summary, full resource index, and function/loot/predicate/advancement ids |
| `functionSource` | `id`, optional source file, and reconstructed function source |
| `versions` | Detailed supported profiles with Java/data/pack format values |
| `coverage` | Coverage plus `failures` and `passed`; accepts `minimumLine`, `minimumFunction`, `include`, `exclude` |
| `resetCoverage` | `{reset:true}` |
| `completions` | Ranged suggestions, inline hint, and multiline hints for `buffer`/`cursor` |
| `checkCommand` | Non-mutating `{valid,severity,code?,message}` against a copied validation world |
| `checkCommands` | Accepts a `commands` array and returns `{checks:[...]}` from one isolated preview world; valid earlier commands contribute preview state to later checks while the active world remains unchanged |

### Checkpoints

`saveCheckpoint`, `restoreCheckpoint`, and `deleteCheckpoint` accept `name` (default `default`) and return the action, whether it changed anything, all names, and current state. `checkpoints` returns `{names}`. Core checkpoint rules still apply: 1–64 safe ASCII name characters, at most 32 checkpoints, command-boundary operations, and resources/budgets outside the saved world copy.

## Paged event streams

`outputs`, `traces`, and `eventTraces` accept a zero-based `from` cursor:

```json
{"id":"events-1","method":"outputs","params":{"from":12}}
```

```json
{"id":"events-1","ok":true,"result":{"from":12,"total":15,"outputs":[{},{},{}]}}
```

After successfully processing the array, store `total` as the next `from`. A negative cursor is clamped to zero; a cursor beyond the end produces an empty array and the current `total`. Cursors belong to the active sandbox: reset them after `create*`/`open` or `resetWorld`.

## Partial errors and interrupt

If a tracked operation raises `SandboxException`, the failure contains `error.partial`:

```json
{"id":"5","ok":false,"error":{"code":"COMMAND_ERROR","message":"...","partial":{"commandsCompleted":2,"outputs":[],"traces":[],"eventTraces":[],"snapshotDiffs":[],"state":{}}}}
```

The partial payload is authoritative for already-completed work. Apply its events/diffs/state before showing the error; retrying the whole operation without restoring a checkpoint may execute earlier commands twice.

`interrupt` bypasses the ordinary single-thread request queue, sets cooperative cancellation, and returns:

```json
{"id":"cancel-1","ok":true,"result":{"requested":true,"boundary":"command"}}
```

Cancellation is observed at command/tick boundaries and does not roll back completed commands. Ordinary requests execute serially, so a client can queue them, but should usually enforce one mutating operation at a time in its UI.

## Render PNG

::: warning The caller must pass client resources
`render`/`screenshot` does not scan `.minecraft`, download a client, or derive an asset path from the active `version`. Explicitly provide `minecraftAssets` when real Minecraft models/textures are required. `resourcePacks` and `playerSkins` are also paths on the machine running `serve`.
:::

```json
{"id":"render-1","method":"render","params":{"minecraftAssets":"D:/Minecraft/versions/26.2/26.2.jar","resourcePacks":["D:/packs/base.zip","D:/packs/override"],"playerSkins":{"Alex":"D:/skins/alex.png"},"width":960,"height":540,"cameraPlayer":"Alex","strictAssets":true}}
```

Camera priority is `cameraPlayer`, then `cameraEntity`, then a fixed `position:[x,y,z]` with optional `yaw`, `pitch`, and `dimension`, otherwise automatic. Other fields are `fieldOfView`, `renderDistance`, `transparentBackground`, `showHud`, and `showDebugOverlay`.

When `minecraftAssets` is omitted, the renderer uses deterministic fallbacks; it never downloads or discovers a JAR. The response contains `mimeType:"image/png"`, `encoding:"base64"`, `data`, dimensions, asset sources, diagnostics, scene counts, timings, and the explicit approximate-lighting/non-parity flags. Decode `data` promptly. The encoded PNG may not exceed 16 MiB.

## Process ownership and recovery

- One process owns one active sandbox. Start separate processes for isolated documents or security domains.
- Treat EOF, invalid JSON, a child-process exit, and a write failure as session loss. Reject outstanding requests, retain unsaved editor text, restart, wait for hello, recreate, then reapply the fixture/checkpoint strategy.
- Do not expose an unrestricted `serve` child directly to untrusted remote clients. Pack, manifest, function, world, and render parameters can read host paths available to the process.
- Validate or constrain paths in the parent integration and run the child with the least filesystem access appropriate to the workspace.

## Limits

- A request line is capped at 1,048,576 characters. An oversized line receives a failure with `id: null` because the full envelope is not parsed.
- At most 64 ordinary requests may wait in the pending queue; a full queue returns `INPUT_FORMAT`.
- Rendered PNG bytes are capped at 16 MiB before base64 encoding.
- Sandbox command/output/snapshot limits are set at creation and still apply.
- JSONL has no binary frames; large renders have base64 memory overhead.

## Related pages

- [CLI Reference](/en/reference/cli)
- [Jupyter](/en/integrations/jupyter)
- [Manifest Reference](/en/reference/manifest)
- [Renderer API](/en/reference/renderer-api)
- [Reports and Observability](/en/reference/reports-observability)
