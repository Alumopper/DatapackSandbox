# Interactive playground

`@datapack-sandbox/vitepress-playground` adds persistent MCFunction notebook cells to a VitePress page. The browser only contains the editor and UI. It connects to the separately deployed Playground API, which creates an isolated Datapack Sandbox JVM session.

[[playground-demo]]

The example above uses `VITE_DPS_PLAYGROUND_API_URL`, falling back to `http://127.0.0.1:8080`. When no API is reachable, the component deliberately displays **Playground unavailable** with a retry action.

## Install and embed

Install the independently versioned package in a VitePress project:

```bash
npm install @datapack-sandbox/vitepress-playground
```

Then use it from any Markdown page:

```md
<script setup>
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const notebook = {
  version: '26.2',
  cells: [
    { type: 'markdown', source: '# Try it' },
    { type: 'code', source: 'setblock 0 0 2 minecraft:stone' }
  ]
}
</script>

<DpsPlayground
  api-url="https://playground.example.com"
  :notebook="notebook"
  :render="{ auto: true, width: 960, height: 540 }"
/>
```

The component is SSR-safe: it does not open a WebSocket until mounted in the browser.

## Component API

| Prop | Type | Default | Purpose |
| --- | --- | --- | --- |
| `notebook` | `PlaygroundNotebook` | required | Initial document and Minecraft profile. |
| `api-url` | `string` | required | HTTP(S) API base URL or full WS(S) endpoint. |
| `render` | `{ auto?, width?, height? }` | `{ auto: true, width: 960, height: 540 }` | Automatic or explicit approximate rendering. |
| `theme` | `auto \| light \| dark` | `auto` | Follow VitePress or force a color scheme. |
| `layout` | `notebook \| compact` | `notebook` | Cell spacing and toolbar density. |
| `read-only` | `boolean` | `false` | Lock source editing while retaining run controls. |
| `site-id` | `string` | unset | Optional deployment/example identifier, at most 128 characters. |

The component emits `ready(sessionId)` and `error({ code, message })`. Code cells support Run/Rerun, Render, <kbd>Tab</kbd> to accept the selected completion, and <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>Enter</kbd> to run. The document toolbar provides Run all, Interrupt, Reset sandbox, Restore example, and Reconnect. **Reset sandbox** clears world state but keeps edited source; **Restore example** restores the initial notebook source, clears output, and resets the online sandbox session.

### Notebook schema

```ts
interface PlaygroundNotebook {
  version: string
  cells: Array<
    | { id?: string; type: 'markdown'; source: string }
    | { id?: string; type: 'code'; source: string }
  >
  preset?: string
}
```

Cell IDs should be stable if the page records output externally. Missing and duplicate IDs are made unique inside the component. Markdown is rendered with raw HTML disabled. Modified code remains in browser memory for the life of the component; anonymous sessions and worlds are not persisted.

## Deploy the API

The image build compiles the Java 25 gateway and the existing standalone CLI JAR:

```bash
docker build -f playground-api/Dockerfile -t datapack-sandbox/playground-api .
docker run --rm -p 8080:8080 \
  -e DPS_ALLOWED_ORIGINS=https://docs.example.com \
  -e DPS_ALLOWED_PROFILES=26.2 \
  datapack-sandbox/playground-api
```

Or start `playground-api/compose.yaml` from the repository:

```bash
docker compose -f playground-api/compose.yaml up --build
```

The service exposes `GET /health` and WebSocket `/v1/playground`. Each accepted session owns one `java -jar datapack-sandbox-cli.jar serve --protocol jsonl` process. Closing the socket, requesting `session.close`, or reaching the idle timeout destroys that process and its descendants. The container runs as an unprivileged user; the provided Compose file also uses a read-only root filesystem, a bounded `/tmp`, and `no-new-privileges`.

### Origins and reverse proxies

`DPS_ALLOWED_ORIGINS` is a comma-separated list of exact browser origins, including scheme and optional port. WebSocket upgrades without an allowed `Origin` are closed with policy code `ORIGIN_NOT_ALLOWED`. Use `*` only for a deliberately public endpoint. A reverse proxy must forward `Upgrade`, `Connection`, and `Origin`, and should terminate TLS so HTTPS documentation connects with `wss://`.

The `/health` endpoint returns an `Access-Control-Allow-Origin` header only for configured origins. WebSockets do not use normal CORS preflight; the gateway enforces the `Origin` header itself.

### Session limits

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `DPS_IDLE_TIMEOUT_MS` | `600000` | Idle anonymous-session lifetime. |
| `DPS_EXECUTION_TIMEOUT_MS` | `5000` | Maximum cell/check/render backend request time. |
| `DPS_MAX_CELL_BYTES` | `65536` | UTF-8 source/request payload limit. |
| `DPS_MAX_OUTPUT_BYTES` | `1048576` | Maximum structured event or base64 PNG payload. |
| `DPS_MAX_RENDER_WIDTH` / `DPS_MAX_RENDER_HEIGHT` | `1920` / `1080` | Render dimension ceiling. |
| `DPS_REQUESTS_PER_MINUTE` | `120` | Per-WebSocket fixed-window request limit. |
| `DPS_MAX_SESSIONS` | `64` | Concurrent JVM session ceiling. |
| `DPS_MAX_COMMANDS` | `10000` | Sandbox command budget per run. |
| `DPS_MAX_OUTPUT_EVENTS` | `2000` | Sandbox output-event budget. |

Only profiles in `DPS_ALLOWED_PROFILES` can be requested. Client-supplied filesystem paths, resource packs, skins, manifests, and uploaded datapacks are never forwarded to `serve`.

### Read-only presets

Mount preset packs read-only and point `DPS_PRESETS_FILE` at a server-owned JSON file:

```json
{
  "starter": {
    "packs": ["/presets/starter"]
  }
}
```

```bash
docker run --rm -p 8080:8080 \
  -v "$PWD/presets:/presets:ro" \
  -v "$PWD/presets.json:/config/presets.json:ro" \
  -e DPS_PRESETS_FILE=/config/presets.json \
  -e DPS_ALLOWED_ORIGINS=https://docs.example.com \
  datapack-sandbox/playground-api
```

Preset IDs must match `[a-z0-9][a-z0-9._-]{0,63}`. Pack paths are normalized and checked at startup. A notebook requests one by setting `preset: 'starter'`; any unknown preset returns `PRESET_NOT_ALLOWED`. Load functions run once when the preset session is created.

## WebSocket protocol

Every client request is a JSON object with a string or numeric `id` and a `type`. Events echo it as `requestId`.

| Client request | Purpose | Terminal event |
| --- | --- | --- |
| `session.create` | Choose an allowed `version`, optional `preset`, render defaults, and `siteId`. | `session.ready` |
| `cell.execute` | Upsert one synthetic function and run it in the persistent world. | `cell.status` with `idle` |
| `cell.complete` | Complete the current command buffer and cursor. | `cell.output` with `kind: completion` |
| `cell.check` | Validate every nonblank, non-comment source line without mutating the world. | `diagnostic` |
| `cell.render` | Explicitly render current state. | `cell.render` |
| `session.interrupt` | Request cancellation at the next command boundary. | `cell.status` |
| `session.reset` | Clear world state while keeping profile, preset, and cell sources. | `session.ready` |
| `session.close` | Destroy the JVM process. | `session.closed` |

Execution sends `cell.status: running`, then `cell.output` or `cell.error`, optional `cell.render`, and finally `cell.status: idle`. `cell.output.result` contains command count, new command outputs and traces, state diffs, and concise state metadata. Raw JSON is collapsed by default in the UI.

Diagnostics contain `cellId`, one-based source `line`, command text, stable sandbox diagnostic code, severity, and message. Completion replacement offsets are relative to the requested command line.

## Rendering behavior

PNG data is sent inline as base64 with `mimeType: image/png`. Automatic rendering occurs only after successful execution; Render captures the current state without executing the cell. Both paths enforce the configured dimensions and response-size ceiling.

Rendering uses the project's approximate clean-room renderer. The `session.ready` capability explicitly reports `visualParity: false`; screenshots must not be presented as pixel-perfect vanilla output.

## Error codes and recovery

| Code | Recovery |
| --- | --- |
| `INVALID_REQUEST`, `CELL_TOO_LARGE`, `RENDER_SIZE_LIMIT`, `OUTPUT_LIMIT`, `RATE_LIMIT`, `BUSY`, `REQUEST_TIMEOUT` | Correct or retry the request; the world is retained. |
| `PROFILE_NOT_ALLOWED`, `PRESET_NOT_ALLOWED` | Select a server-enabled value. |
| `INTERRUPTED` | The command stopped at a command boundary; inspect partial metadata before continuing. |
| `EXECUTION_TIMEOUT` | A non-mutating check, completion, render, create, or reset exceeded its time limit; retry if appropriate. |
| `RESET_REQUIRED` | An execution timed out and may have partially changed state. Use **Reset sandbox** before another operation. |
| `SESSION_LOST` | The isolated JVM exited. Reconnect to create a new non-persistent world. |
| `ORIGIN_NOT_ALLOWED` | Add the exact docs origin to server configuration. |
| `SERVER_BUSY` | Retry when another anonymous session has closed. |

Sandbox command errors retain the cell, line, command, diagnostic code, partial outputs, traces, and state changes when the backend provides them. Recoverable command failures do not close the session.

## Unavailable and offline behavior

The browser cannot execute Datapack Sandbox locally. If DNS, TLS, proxy upgrade, origin policy, or the API fails, the component keeps the notebook source visible and displays a clear unavailable panel with Retry/Reconnect. Sites can additionally place a static `.mcfunction` example below the component; there is no silent browser-only simulation and no claim that execution succeeded.
