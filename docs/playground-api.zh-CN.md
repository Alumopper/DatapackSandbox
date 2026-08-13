# Playground API 参考

## 适用场景

完成基础 [Playground 嵌入](/guide/playground)后，如果需要精确的 Vue component 边界、共享 Worker session、直接 controller/client 调用、import policy、viewport event 或 recoverable error 处理，使用本页。该 package 在浏览器 Worker 中本地运行 clean-room runtime，不调用托管的 Datapack Sandbox 服务。

## 前置条件

```ts
import {
  DpsCell,
  DpsPlayground,
  DpsViewport,
  PlaygroundSessionController,
} from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
```

站点必须通过 HTTP(S) 提供；module Worker 不能从 `file://` 运行。若 Vite 把 package Worker 移到 dependency optimizer cache 却未复制 asset，把 `@datapack-sandbox/vitepress-playground` 加入 `vite.optimizeDeps.exclude`，清理 optimizer cache 后重启。

::: warning Web 渲染也必须显式提供客户端资源
浏览器不能使用 JVM 文件路径，package 也不会扫描 `.minecraft` 或根据 `notebook.version` 下载 client JAR。要显示真实 Minecraft model/texture，用户或宿主必须显式把匹配版本的 client JAR 作为本地 bytes 导入；否则 viewport 使用确定性 fallback。
:::

## 最小可运行组件

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { DpsCell, type PlaygroundEvent } from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say hello from the browser')
const lastResult = ref<PlaygroundEvent>()
</script>

<template>
  <DpsCell
    v-model="source"
    version="26.2"
    sandbox-id="guide-demo"
    :render="{ auto: false, width: 960, height: 540 }"
    :allow-import="true"
    @executed="lastResult = $event"
    @error="console.error($event)"
  />
</template>
```

只有确实需要共享同一 world 的组件才使用相同稳定 `sandboxId`；同一页面上无关文档应生成不同 id。

## 选择组件

| 组件 | 主要用途 | 状态所有权 |
| --- | --- | --- |
| `DpsCell` | 单个可编辑 `.mcfunction` cell，带诊断和结果 | 自有 session、`sandboxId` 页面 session 或显式 `session` |
| `DpsPlayground` | 含 markdown/code 的多 cell notebook 与 action bar | 同样三种模式 |
| `DpsViewport` | 实时 scene、camera、键盘/触控 input、frame stats | 加入 session 或从 notebook 新建 |

`DpsCell` 默认 `version="26.2"`；`DpsPlayground` 需要 `PlaygroundNotebook`，其 `version` 选择打包的 browser profile。Notebook cell 为 `{type:'markdown', source}` 或 `{type:'code', source}`，可带稳定 `id`。

## 组件 props

组件共享一套有意统一的概念：

| 分组 | Props | 用途 |
| --- | --- | --- |
| Session | `session`, `sandboxId`, `siteId`, `workerUrl` | 显式、页面级或自有 Worker session |
| Runtime | `version`, `notebook`, `dependencies`, `presets`, `limits` | Profile、source、远程 pack、policy、budget |
| Rendering | `render`, `viewport`, `animation` | 静态尺寸/auto-render、实时控制、GIF capture |
| UI | `theme`, `layout`, `readOnly`, `compact`, `showDetails`, `actions` | 展示与可用操作 |
| Import | `allowImport` | 启用用户驱动的本地文件/目录/archive import |
| Localization | `locale`, `labels` | 内建 `en`/`zh-CN` 文案与 label override |

`DpsPlayground` 必需 `notebook`，另支持 `checkpointName`。`DpsCell` 必需 `modelValue`、发出 `update:modelValue`，并增加 `cellId`/`dependencies`/`compact`。`DpsViewport` 可接显式 `session`、从 `notebook` 建自有 session，或加入 `sandboxId`；viewport options 控制 FPS/tick rate、autoplay/tick function、input player、keyboard/touch/pointer lock、toolbar、FOV、移动速度、鼠标灵敏度与 pixel ratio 范围。

`actions` 把 action name 映射到 `primary`、`menu`、`hidden`。公开 action 包括 run/render/run-all、interrupt、save/return checkpoint、capture/export GIF、reset/restore example、imports、restart。

## Events

公开 payload 使用 `PlaygroundEvent` 或更窄的导出类型。主要事件族：

| Event | 含义/关键字段 |
| --- | --- |
| `ready` | Worker session 可用 |
| `error` | `PlaygroundClientError` 或结构化 error data |
| `executed` | Cell 到达 terminal idle result |
| `gif` | 带 MIME type/bytes 的 GIF event |
| `checkpoint` | Save/restore/delete 结果 |
| `play-state` | Playing/paused simulation state |
| `camera-change` | Position、yaw、pitch、speed、auto/manual state |
| `input` | 规范化 keyboard/mouse/touch input |
| `frame-stats` | FPS、frame time、pixel ratio、triangles、revision |
| `context-lost` | WebGL context 无法继续 present scene |

`DpsCell` 额外发出 `update:modelValue`；`DpsCell` / `DpsPlayground` 按 action 发出相应 execution/GIF/checkpoint event。外层 view 销毁时要取消注册 listener。

## 显式共享一个 session

最短的页面级共享方式是使用相同 `sandboxId`。第一个 owner 必须提供 notebook/version，后续 joiner 必须使用相同 profile；版本冲突会被拒绝，不会在同一 id 下悄悄重建另一 world。

需要确定所有权时创建 controller：

```ts
import {
  PlaygroundSessionController,
  type PlaygroundEvent,
} from '@datapack-sandbox/vitepress-playground'

const controller = new PlaygroundSessionController({
  notebook: {
    version: '26.2',
    cells: [{ id: 'main', type: 'code', source: 'say shared' }],
  },
  render: { auto: false, width: 960, height: 540 },
  limits: { maximumCommands: 10_000 },
})

const stopEvents = controller.onEvent((event: PlaygroundEvent) => {
  if (event.type === 'cell.output') console.log(event.output)
})
const stopActivity = controller.onActivity(({ busy, operation, pending }) => {
  updateToolbar({ busy, operation, pending })
})

await controller.connect()
await controller.execute('main', 'say shared')

// Owning page 销毁时：
stopEvents()
stopActivity()
controller.dispose()
```

把同一 controller 通过 `session` prop 传给 cell、playground、viewport，只有 owner 调用 `dispose()`。Controller 串行化 exclusive mutation，文档隐藏时暂停 playback，并避免 scene subscription 建立互相竞争的 Worker world。

## Controller 操作

| 分组 | 方法 |
| --- | --- |
| Lifecycle | `connect`, `reset`, `restoreExample`, `dispose` |
| Code | `execute`, `complete`, `check`, `readFunction`, `interrupt` |
| Rendering | `render`, `subscribeScene`, `refreshScene` |
| Simulation/input | `play`, `pause`, `step`, `dispatchInput` |
| Checkpoints | `saveCheckpoint`, `restoreCheckpoint`, `deleteCheckpoint`, `listCheckpoints` |
| Animation | `captureAnimationFrame`, `exportAnimation`, `clearAnimation` |
| Imports | `importEntries`, `importArchive` |
| Subscriptions | `onEvent`, `onConnection`, `onActivity` |

`complete`、`check`、`readFunction` 是读取型调用。World-changing/capture 操作由 controller 串行化；activity 包含 active operation、cell id 和 pending 数。`dispatchInput` 不进入 exclusive queue，以保持输入响应。

## 导入数据包、资源包、世界和客户端资源

启用 `allowImport` 后，用户可点 **Import files**、选择目录或拖放支持内容。直接集成可传 entries 或 archive：

```ts
await controller.importArchive(
  'datapack',
  datapackFile.name,
  await datapackFile.arrayBuffer(),
)

await controller.importArchive(
  'client-jar',
  clientJar.name,
  await clientJar.arrayBuffer(),
)
```

`PlaygroundImportKind` 为 `datapack | resource-pack | client-jar | world`。Client JAR import 有特殊边界：archive reader 只保留 `assets/` 下受支持的 entry。用户选择的 bytes 只存在于内存 Worker session，dispose/rebuild 后消失。

`PlaygroundDependencySource.kind` 只有 `datapack | resource-pack`。Client JAR 不能声明成 URL dependency，必须由用户/宿主显式提供 bytes。这样 `version` 不会悄悄变成下载指令，资产与许可边界对宿主保持透明。

## Preset 与 URL dependency

Preset 是宿主预注册的 `{url, sha256?}`，notebook 只能引用已注册名字。Dependency 是按顺序加载的 `{kind,url,sha256?,name?}`。两者都带 same-origin credentials fetch，并受 import limits 约束。

影响可执行示例的内容应使用 SHA-256。Integrity 依赖 `crypto.subtle`；不匹配对本次请求不可恢复。URL allow-list 放在应用代码中——不应允许 notebook 或用户 Markdown 任意指定远程依赖。

## 底层 Worker client

`PlaygroundWorkerClient` 暴露 `connect`、`createSession`、execution/check/completion、render、checkpoint、animation、playback/input、viewport subscription、imports、reset/interrupt、event/connection subscriptions 与 `close`。只有 controller 的串行化 ownership 不适合自定义架构时才直接使用。

Request 使用生成的 `web-N` id，并在各操作专属 terminal event 上 resolve。默认 timeout 15 秒；超时后 client 请求 Worker interrupt，若默认 2 秒 grace 内仍不停止，就 terminate/rebuild Worker 并按记忆的 session config 重建。内存执行状态/checkpoint/import 不是持久恢复存储；source document 必须保存在 Worker 外。

## 浏览器限制

默认值保守，可通过 `limits` 下调：

| 限制 | 默认值 |
| --- | ---: |
| Cell source | 64 KiB |
| 总 output bytes | 1 MiB |
| Commands | 10,000 |
| Output events | 2,000 |
| Render 尺寸 | 1920 × 1080（每轴只可在 16–4096 内配置） |
| Checkpoint 数/单个 bytes | 32 / 8 MiB |
| Animation frames/bytes | 120 / 64 MiB |
| Import 展开 bytes/files | 64 MiB / 16,384 |

无效配置值会回落到默认。Import 会在 streaming 与 archive 展开阶段同时受限，包括没有可信 `Content-Length` 的响应。

## 错误与恢复

`PlaygroundClientError` 包含 `code`、`message`、`recoverable` 与可选 `details`。常见类别：

- setup：`API_UNAVAILABLE`、`PROFILE_NOT_ALLOWED`、`SANDBOX_ID_INVALID`、`SANDBOX_VERSION_MISMATCH`、`NOTEBOOK_REQUIRED`；
- session：`SESSION_LOST`、`BUSY`、`WORKER_RUNTIME_ERROR`；
- preset/dependency：`PRESET_NOT_ALLOWED`、`PRESET_FETCH_FAILED`、`PRESET_INTEGRITY_FAILED`、`DEPENDENCY_FETCH_FAILED`、`DEPENDENCY_INTEGRITY_FAILED`；
- import/budget：`IMPORT_SIZE_LIMIT`、`IMPORT_FILE_LIMIT`、`CELL_TOO_LARGE`、`RENDER_SIZE_LIMIT`、`ANIMATION_FRAME_LIMIT`。

Recoverable fetch/transient error 只能带 backoff 和可见状态重试。Non-recoverable error 应禁用相关 action，或从外部保存的 notebook 重建。`context-lost` viewport 可重建而不假定 Worker world 丢失；`SESSION_LOST` 则需要重建 session。

## 部署与安全注意事项

- Packaged Worker 必须以 JavaScript MIME type 提供。自定义跨域 `workerUrl` 需要兼容 CORS 与 CSP `worker-src`。
- Worker 执行在本地，但 preset/dependency fetch 仍访问网络，import 会处理不可信 archive；应保持 byte/file/command limits。
- 页面销毁时终止 owned Worker，否则导航后可能残留内存与 simulation timer。
- Client assets、pack、world data 可能有版权或隐私属性。默认只留在本地；嵌入页面不得未经另一个显式动作就把它们上传。

## 限制

- Browser profile 与 clean-room engine 只建模有限 datapack runtime，不是完整 Minecraft client/server。
- 导入 client assets 只启用受支持 model/texture，不会让 `visualParity` 变成原版保证。
- Worker memory 是临时的；notebook source 和有意导出的结果必须在外部持久化。
- 共享 session 的组件会观察同一 mutation，必须一致展示 busy/activity state。

## 相关页面

- [Playground 嵌入](/guide/playground)
- [Playground 样式](/guide/playground-styling)
- [渲染与实时视窗](/guide/rendering-notebook)
- [Renderer API](/reference/renderer-api)
- [Serve JSONL](/reference/serve-jsonl)
