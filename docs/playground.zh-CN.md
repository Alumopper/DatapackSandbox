# 交互式 Playground

`@datapack-sandbox/vitepress-playground` 为 VitePress 页面提供带持久状态的 MCFunction Notebook。浏览器仅加载编辑器与界面；实际执行通过 WebSocket 连接到单独部署的 Playground API，由后者创建隔离的 Datapack Sandbox JVM 会话。

[[playground-demo]]

上方示例读取 `VITE_DPS_PLAYGROUND_API_URL`，未配置时使用 `http://127.0.0.1:8080`。API 不可达时，组件会明确显示“Playground unavailable”和重试按钮，不会静默假装执行成功。

## 安装与嵌入

```bash
npm install @datapack-sandbox/vitepress-playground
```

在任意 VitePress Markdown 页面中使用：

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

组件兼容 SSR：只有在浏览器挂载后才会建立 WebSocket。

## 组件 API

| 属性 | 类型 | 默认值 | 用途 |
| --- | --- | --- | --- |
| `notebook` | `PlaygroundNotebook` | 必填 | 初始文档与 Minecraft profile。 |
| `api-url` | `string` | 必填 | HTTP(S) API 基址或完整 WS(S) 地址。 |
| `render` | `{ auto?, width?, height? }` | `{ auto: true, width: 960, height: 540 }` | 自动/手动近似渲染。 |
| `theme` | `auto \| light \| dark` | `auto` | 跟随 VitePress 或强制配色。 |
| `layout` | `notebook \| compact` | `notebook` | 单元格间距与工具栏密度。 |
| `read-only` | `boolean` | `false` | 禁止修改源码，但保留运行按钮。 |
| `site-id` | `string` | 未设置 | 可选站点/示例标识，最长 128 字符。 |

组件会发出 `ready(sessionId)` 和 `error({ code, message })`。代码格支持运行/重新运行、渲染、用 <kbd>Tab</kbd> 确认当前补全项，以及用 <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>Enter</kbd> 运行。文档工具栏支持全部运行、中断、重置沙盒、恢复示例和重连。**Reset sandbox** 只清空世界状态并保留已编辑源码；**Restore example** 会恢复初始 Notebook 源码、清除输出，并重置在线沙盒会话。

### Notebook 结构

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

需要外部记录输出时应提供稳定的 cell ID。组件会为缺失或重复的 ID 生成唯一值。Markdown 禁止原始 HTML。修改后的源码仅保留在当前浏览器组件内；匿名会话和世界均不持久化。

## 部署 API

镜像构建会同时编译 Java 25 网关和现有 CLI fat JAR：

```bash
docker build -f playground-api/Dockerfile -t datapack-sandbox/playground-api .
docker run --rm -p 8080:8080 \
  -e DPS_ALLOWED_ORIGINS=https://docs.example.com \
  -e DPS_ALLOWED_PROFILES=26.2 \
  datapack-sandbox/playground-api
```

也可直接使用仓库内的 Compose：

```bash
docker compose -f playground-api/compose.yaml up --build
```

服务提供 `GET /health` 和 WebSocket `/v1/playground`。每个已接受会话独占一个 `java -jar datapack-sandbox-cli.jar serve --protocol jsonl` 进程；断开连接、发送 `session.close` 或空闲超时都会销毁该进程及其子进程。容器以非特权用户运行，Compose 还启用了只读根文件系统、受限 `/tmp` 和 `no-new-privileges`。

### Origin 与反向代理

`DPS_ALLOWED_ORIGINS` 是逗号分隔的精确浏览器 Origin（含协议和可选端口）。不在允许列表中的 WebSocket 会以 `ORIGIN_NOT_ALLOWED` 策略错误关闭。仅在确实需要公开任意来源时使用 `*`。反向代理必须转发 `Upgrade`、`Connection` 和 `Origin`；HTTPS 文档应通过 TLS 终止后连接 `wss://`。

`/health` 只会向允许来源返回 `Access-Control-Allow-Origin`。WebSocket 不走常规 CORS 预检，因此网关直接检查 `Origin`。

### 会话限制

| 环境变量 | 默认值 | 含义 |
| --- | ---: | --- |
| `DPS_IDLE_TIMEOUT_MS` | `600000` | 匿名会话空闲寿命。 |
| `DPS_EXECUTION_TIMEOUT_MS` | `5000` | 单格/检查/渲染的最长后端时间。 |
| `DPS_MAX_CELL_BYTES` | `65536` | UTF-8 源码/请求上限。 |
| `DPS_MAX_OUTPUT_BYTES` | `1048576` | 结构化事件或 base64 PNG 上限。 |
| `DPS_MAX_RENDER_WIDTH` / `DPS_MAX_RENDER_HEIGHT` | `1920` / `1080` | 渲染尺寸上限。 |
| `DPS_REQUESTS_PER_MINUTE` | `120` | 每条 WebSocket 的分钟请求上限。 |
| `DPS_MAX_SESSIONS` | `64` | 并发 JVM 会话上限。 |
| `DPS_MAX_COMMANDS` | `10000` | 每次运行的沙盒命令预算。 |
| `DPS_MAX_OUTPUT_EVENTS` | `2000` | 沙盒输出事件预算。 |

客户端只能请求 `DPS_ALLOWED_PROFILES` 中的版本。任意文件系统路径、资源包、皮肤、manifest 和用户上传 datapack 都不会转发给 `serve`。

### 只读 preset

把 preset pack 以只读方式挂载，并让 `DPS_PRESETS_FILE` 指向服务端拥有的 JSON：

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

Preset ID 必须匹配 `[a-z0-9][a-z0-9._-]{0,63}`。pack 路径会在启动时规范化并检查。Notebook 通过 `preset: 'starter'` 请求；未知值返回 `PRESET_NOT_ALLOWED`。创建 preset 会话时会运行一次 load function。

## WebSocket 协议

每个客户端请求都是 JSON 对象，必须带字符串/数字 `id` 和 `type`；服务端事件用 `requestId` 回显。

| 客户端请求 | 用途 | 终止事件 |
| --- | --- | --- |
| `session.create` | 选择允许的 `version`、可选 `preset`、渲染默认值与 `siteId`。 | `session.ready` |
| `cell.execute` | 更新一个合成 function，并在持续世界中执行。 | `cell.status: idle` |
| `cell.complete` | 为当前命令缓冲区和光标位置补全。 | `cell.output`（`kind: completion`） |
| `cell.check` | 无副作用检查每个非空、非注释源码行。 | `diagnostic` |
| `cell.render` | 显式渲染当前状态。 | `cell.render` |
| `session.interrupt` | 在下一命令边界请求取消。 | `cell.status` |
| `session.reset` | 清空世界，保留 profile、preset 与 cell 源码。 | `session.ready` |
| `session.close` | 销毁 JVM 进程。 | `session.closed` |

执行依次发送 `cell.status: running`、`cell.output` 或 `cell.error`、可选 `cell.render`，最后发送 `cell.status: idle`。`cell.output.result` 包含命令数、新输出与 trace、状态差异和简要状态元数据；界面默认折叠原始 JSON。

诊断含 `cellId`、从 1 开始的源码行、命令文本、稳定诊断码、严重度与消息。补全替换偏移量相对于本次请求的命令行。

## 渲染行为

PNG 使用 base64 内联发送并标记 `mimeType: image/png`。自动渲染只在执行成功后发生；“Render”只捕获当前状态，不执行该 cell。两者都会检查尺寸和响应大小上限。

渲染器是项目现有的 clean-room 近似实现。`session.ready` 能力明确报告 `visualParity: false`，不得把截图宣称为像素级原版一致。

## 错误码与恢复

| 错误码 | 恢复方式 |
| --- | --- |
| `INVALID_REQUEST`、`CELL_TOO_LARGE`、`RENDER_SIZE_LIMIT`、`OUTPUT_LIMIT`、`RATE_LIMIT`、`BUSY`、`REQUEST_TIMEOUT` | 修正或重试；世界会保留。 |
| `PROFILE_NOT_ALLOWED`、`PRESET_NOT_ALLOWED` | 改用服务端已允许的值。 |
| `INTERRUPTED` | 命令在边界停止；继续前检查 partial 元数据。 |
| `EXECUTION_TIMEOUT` | 无副作用检查、补全、渲染、创建或重置超时；可按情况重试。 |
| `RESET_REQUIRED` | 执行超时且可能已部分修改状态；必须先使用 **Reset sandbox** 重置沙盒。 |
| `SESSION_LOST` | 隔离 JVM 已退出；重连会创建新的非持久世界。 |
| `ORIGIN_NOT_ALLOWED` | 把文档站的精确 Origin 加入服务端配置。 |
| `SERVER_BUSY` | 等待其他匿名会话关闭后重试。 |

沙盒命令失败时，若后端提供，会保留 cell、行号、命令、诊断码、部分输出、trace 和状态差异。可恢复的命令失败不会关闭会话。

## API 不可用时

浏览器无法在本地执行 Datapack Sandbox。当 DNS、TLS、代理升级、Origin 策略或 API 出错时，组件仍会显示 Notebook 源码，并展示清晰的不可用面板和 Retry/Reconnect。站点可在组件下方附带静态 `.mcfunction` 示例；组件不会静默切换到浏览器模拟，也不会声称执行成功。
