# 交互式 Playground

`@datapack-sandbox/vitepress-playground` 可在 VitePress 页面中嵌入带持久状态的 MCFunction Notebook。命令执行、补全、诊断、导入文件、世界状态和近似渲染全部留在独立浏览器 Worker 内；无需 Java 服务、WebSocket 地址、Docker 镜像或 CORS 来源白名单。

[[playground-demo]]

上方示例会在组件挂载后创建一个隔离 Worker。用户文件以可转移 `ArrayBuffer` 读入，不会上传，也不会写入 IndexedDB/OPFS；刷新页面即丢弃会话。

## 安装

```bash
npm install @datapack-sandbox/vitepress-playground
```

在 VitePress 主题或仅客户端 Vue 组件中导入：

```ts
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
```

```vue
<DpsPlayground
  :notebook="{
    version: '26.2',
    cells: [
      { type: 'markdown', source: '# 持久的本地世界' },
      { id: 'setup', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
    ],
  }"
  :render="{ auto: true, width: 960, height: 540 }"
/>
```

组件兼容 SSR：只在浏览器挂载后创建 ES module Worker。UI 主入口不会静态打入 Kotlin 运行时；Vite 会生成独立且带内容 hash 的 Worker 文件。

## 单 cell 轻量嵌入

如果例子只需要一个可编辑命令 cell 和执行结果，可使用独立的 `cell` 入口。`DpsCell` 不包含 Notebook 顶栏、交互式导入或 Markdown cell；紧凑标题栏提供执行、渲染、可复用状态点、GIF 帧记录/导出以及 **Reset example**。

[[cell-demo]]

```vue
<script setup lang="ts">
import { ref } from 'vue'
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say 轻量嵌入示例')
</script>

<template>
  <DpsCell
    v-model="source"
    version="26.2"
    :dependencies="[
      { kind: 'datapack', url: '/examples/shared-functions.zip' },
      { kind: 'resource-pack', url: '/examples/preview-assets.zip', sha256: '…' },
    ]"
  />
</template>
```

依赖会在 `ready` 前按声明顺序下载，后面的包覆盖前面的包；支持可选 SHA-256 校验。**Reset example** 后依赖仍保留在会话内存中，Worker 自动重建时会重新加载。默认情况下 **Run** 后不自动生成 PNG，但始终可点击 **Render**；可通过 `:render="{ auto: true, width: 640, height: 360 }"` 开启自动渲染。

默认每次成功执行都会记录一帧 GIF。**Add frame** 不执行源码，只记录当前世界；**Export GIF** 下载全部已记录帧。**Save point** 保存完整的建模世界、输出和 trace，**Return** 可重复回到该点。数据包、资源包和单调递增的安全预算属于会话配置，不属于检查点状态。

每个 `DpsCell` 仍拥有隔离的本地 Worker 会话，并保留补全、诊断及 <kbd>Ctrl/⌘</kbd>+<kbd>Enter</kbd> 执行。组件还公开 `savePoint()`、`returnToPoint()`、`captureAnimationFrame()`、`exportGif()`，并在 `ready`、`executed`、`error` 之外触发 `gif` 和 `checkpoint` 事件。

## 组件 API

| Prop | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `notebook` | `PlaygroundNotebook` | 必填 | 版本、有序 cell 和可选 preset id。 |
| `theme` | `auto \| light \| dark` | `auto` | 显式主题或继承 VitePress 深色模式。 |
| `layout` | `notebook \| compact` | `notebook` | 完整 Notebook 或紧凑间距。 |
| `read-only` | `boolean` | `false` | 禁止编辑源码，但仍可执行。 |
| `render` | `PlaygroundRenderOptions` | 自动，`960×540` | 自动渲染和默认尺寸。 |
| `animation` | `PlaygroundAnimationOptions` | `480×270`、250 ms、循环 | GIF 尺寸、帧延迟、循环次数和执行后自动记录。 |
| `checkpoint-name` | `string` | 组件专用默认名 | 内置 Save point/Return 控件使用的名称。 |
| `presets` | `Record<string, { url; sha256? }>` | `{}` | 从同源或允许 CORS 的 URL 延迟获取静态 ZIP。 |
| `allow-import` | `boolean` | `true` | 显示文件/目录导入按钮并接受拖放。 |
| `limits` | `PlaygroundBrowserLimits` | 浏览器默认值 | 每实例稳定性预算和 watchdog 时序。 |
| `worker-url` | `string` | 包内资源 | 仅在自行托管 Worker 构建物时覆盖。 |
| `site-id` | `string` | 省略 | 创建会话时携带的嵌入站点标签。 |

本地会话及可选 preset 就绪后触发 `ready(sessionId)`；执行、导入、完整性校验或生命周期失败会触发 `error({ code, message })`。

Notebook schema 保持不变：

```ts
interface PlaygroundNotebook {
  version: string
  preset?: string
  cells: Array<
    | { id?: string; type: 'markdown'; source: string }
    | { id?: string; type: 'code'; source: string }
  >
}
```

各 cell 按顺序在同一持久世界中执行。**Reset sandbox** 会创建全新世界但保留编辑后的源码；**Restore example** 会恢复初始 Notebook、清空输出并创建全新世界。

## Preset 与导入

按 id 注册不可变的 preset ZIP：

```vue
<DpsPlayground
  :notebook="{ version: '26.2', preset: 'starter', cells }"
  :presets="{
    starter: {
      url: '/playground-presets/starter.4f2d.zip',
      sha256: 'b4f0…共 64 个十六进制字符…',
    },
  }"
/>
```

只有选中 preset 时才会下载 ZIP。配置 `sha256` 后，浏览器会先校验完整性，再把归档转移给 Worker。

内置文件选择、目录选择和拖放入口可接收 datapack ZIP/目录、资源包、client JAR 以及世界目录/ZIP。无法唯一识别的输入会显示类型选择器。所有虚拟路径统一为 `/`；绝对路径、盘符路径、`..`、控制字符和重复条目都会被拒绝。当前的 `data/<namespace>/function` 以及旧版 `functions` 目录中的函数都会注册给 `function` 命令。

浏览器渲染与 JAR fallback 渲染器共享透视相机、blockstate/model 烘焙、深度缓冲、纹理采样、光照和雾化算法。导入资源包或 client JAR 后，Worker 会使用其中的模型 JSON 与 PNG 纹理；只按需解码当前场景引用的资源，并仅在本次会话内存中保留。

`block_display`、`item_display` 和 `text_display` 在 JVM 与 Web 端使用同一份规范化展示状态，包括 transformation、四种 billboard、亮度和阴影控制、可读样式文字、新版 item definition 解析、generated sprite 像素轮廓挤出、模型 display 变换，以及按 tick 推进的视觉与传送插值。分解变换的平移/缩放使用线性插值，左右旋转四元数使用归一化最短弧 SLERP；16 数字矩阵仍按元素线性插值。例子依赖原版或自定义视觉资源时，应默认导入对应版本的 client JAR 或资源包。

## 限制与生命周期

默认值用于维持浏览器稳定，不代表安全隔离边界：

| 限制 | 默认值 |
| --- | ---: |
| Cell 源码 | 64 KiB |
| 结构化输出 | 1 MiB |
| 单次执行命令数 | 10,000 |
| 输出事件数 | 2,000 |
| 渲染尺寸 | 1,920 × 1,080 |
| 命名检查点 | 32 个，每个 8 MiB |
| GIF 记录 | 120 帧，64 MiB RGBA |
| 导入解压后大小 | 64 MiB |
| 导入文件数 | 16,384 |
| 请求 watchdog | 15 秒 |
| 取消宽限期 | 2 秒 |

执行会在每条 MCFunction 命令边界让出事件循环。**Interrupt** 设置取消标志，因此已经完成的命令状态会保留。如果请求超过宽限期仍不响应，客户端会终止并重建 Worker，以 `SESSION_LOST` 拒绝在途请求，然后自动创建干净会话。

每个组件只拥有一个 Worker，不同组件不会共享世界状态。组件卸载时会终止 Worker，并回收全部渲染 Blob URL。

## Worker 协议

`PlaygroundWorkerClient` 替换已经移除的 WebSocket `PlaygroundClient` 导出，同时保留 request id、请求名、事件名和稳定错误对象。支持的请求包括：

- `session.create`、`session.reset`、`session.interrupt`、`session.close`、`session.import`
- `session.checkpoint.save`、`.restore`、`.delete`、`.list`
- `cell.execute`、`cell.complete`、`cell.check`、`cell.render`
- `animation.capture`、`animation.export`、`animation.clear`

执行会发送 `cell.status`、`cell.output`、`diagnostic`、`cell.render` 和 `cell.error`。PNG 使用 `bytes: ArrayBuffer` 与 `mimeType: image/png`；GIF 导出使用相同的 transferable 结构和 `mimeType: image/gif`，两者都不使用 base64。

常见错误码包括 `INVALID_REQUEST`、`PROFILE_NOT_ALLOWED`、`CELL_TOO_LARGE`、`COMMAND_LIMIT`、`OUTPUT_LIMIT`、`RENDER_SIZE_LIMIT`、`BUSY`、`INTERRUPTED`、`SESSION_LOST`、`CHECKPOINT_NOT_FOUND`、`CHECKPOINT_LIMIT`、`ANIMATION_EMPTY`、`ANIMATION_FRAME_LIMIT`、`ANIMATION_SIZE_LIMIT`、`IMPORT_PATH_INVALID`、`IMPORT_CONFLICT`、`IMPORT_FILE_LIMIT`、`IMPORT_SIZE_LIMIT` 和 `PRESET_INTEGRITY_FAILED`。

## 渲染边界

渲染使用项目的确定性 clean-room 软件光栅器，从 RGBA 结果生成 PNG；元数据明确包含 `lightingModel: approximate` 和 `visualParity: false`。GIF 帧由 JVM/Web 共用的 Kotlin 自适应调色板/LZW 编码器生成，因此相同 RGBA 帧的输出字节一致。不得把截图描述为原版像素级复刻。自定义字体 provider、多层或特殊物品模型、发光轮廓、客户端 light map 与后处理仍不在一致性承诺内。导入资源只是当前会话输入；不支持的资源细节会回退到确定性程序颜色。

## 纯静态部署

正常构建 VitePress：

```bash
npm ci
npm run docs:build
```

## 实时 WebGL 视窗

可向 `DpsPlayground` 或 `DpsCell` 传入 `viewport`，也可单独挂载 `DpsViewport`。共享
`PlaygroundSessionController` 后，多个组件会连接到同一个由 Worker 持有的世界。

播放默认关闭。开始播放后以 20 TPS 推进世界时间和展示实体插值，执行
`#minecraft:tick` 以及可选的 `tickFunction`；卡顿时最多补算 5 tick，页面隐藏时自动暂停。
桌面端支持指针锁定、鼠标观察、WASD、Space、Shift 和滚轮调速；触屏设备显示双摇杆。
输入默认记录给 `Steve`，可从 trace 和 snapshot 观察，但不会隐式模拟原版碰撞、玩家物理、实体 AI 或红石。

实时渲染器位于独立的延迟加载 WebGL2 chunk 中。Worker 按 revision 传输独立的方块、实体、索引和
纹理图集缓冲，镜头移动只更新 uniform。context lost 时暂停播放并在恢复后重建 GPU 资源；静态 PNG
与 GIF 始终使用共享软件渲染器。视窗事件包括 `play-state`、`camera-change`、`input`、
`frame-stats` 与 `context-lost`。

直接部署生成的静态目录。带内容 hash 的 Worker/profile 资源可使用长期 immutable 缓存，HTML 入口保持常规重新验证。自定义 `worker-url` 必须同源，或返回允许 ES module Worker 的响应头。不再涉及 Java 运行时、反向代理 Upgrade、API Origin 白名单或 Docker 服务。

目标浏览器需支持 ES module Worker、transferable `ArrayBuffer`、`createImageBitmap`/OffscreenCanvas、Blob URL，以及用于可选 preset 完整性校验的 Web Crypto。
