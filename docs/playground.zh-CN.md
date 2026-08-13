# 交互式 Playground

## 适用场景

使用 `@datapack-sandbox/vitepress-playground`，可以把可执行的 MCFunction Notebook 或单个命令 cell 嵌入 VitePress。执行、补全、诊断、导入文件、世界状态和近似渲染全部留在浏览器 Worker 中，不需要部署 Java 服务。

[[playground-demo]]

## 前置条件

目标浏览器需要支持 ES module Worker、可转移 `ArrayBuffer`、Blob URL、Web Crypto，以及渲染所需的 `createImageBitmap`/OffscreenCanvas。

```bash
npm install @datapack-sandbox/vitepress-playground
```

在 VitePress 配置中排除开发依赖预构建，确保 Worker URL 仍相对于包内 `dist`：

```ts
import { defineConfig } from 'vitepress'

export default defineConfig({
  vite: {
    optimizeDeps: {
      exclude: ['@datapack-sandbox/vitepress-playground'],
    },
  },
})
```

## 最小可运行示例

```vue
<script setup lang="ts">
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const notebook = {
  version: '26.2',
  cells: [
    { type: 'markdown', source: '# 持久的本地世界' },
    { id: 'setup', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
  ],
}
</script>

<template>
  <DpsPlayground :notebook="notebook" :render="{ auto: true }" />
</template>
```

组件兼容 SSR，只在浏览器挂载后创建独立的 module Worker。刷新或卸载页面会销毁内存会话，不会把导入内容上传或持久化到 IndexedDB/OPFS。

## 完整能力

### 单 cell 嵌入

只有一个可编辑示例时，使用 `/cell` 入口；它保留补全、诊断、快捷执行、渲染、检查点和 GIF，但省略 Notebook 顶栏与 Markdown cell。

[[cell-demo]]

```vue
<script setup lang="ts">
import { ref } from 'vue'
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say 轻量嵌入示例')
</script>

<template>
  <DpsCell v-model="source" version="26.2" />
</template>
```

### Preset、依赖与导入

`presets` 按 id 注册静态 ZIP，并可用 `sha256` 校验；`dependencies` 会在 `ready` 前按声明顺序载入，后面的包覆盖前面的包。内置选择器和拖放入口接受数据包、资源包、client JAR 及世界目录/ZIP。

所有路径都会正规化为 `/`。绝对路径、盘符、`..`、控制字符、重复条目以及超过预算的归档会被拒绝。Minecraft client JAR 只作为模型和纹理资产读取，其中的类不会执行。

### 手动提供客户端资源

Web renderer 不会捆绑或下载 Minecraft 客户端资源，也不能读取服务器/JVM 上的路径或自动访问浏览器所在机器的 `.minecraft`。要获得对应版本的模型和纹理，请在组件中点击 **Import files**（或拖放文件）并选择本地 client JAR；`.jar` 会识别为 `client-jar`。导入只在当前 Worker 内存会话中生效，刷新或销毁 session 后需要重新选择。

自己管理 session 时，必须显式传入浏览器 `File` 的字节：

```ts
await session.connect()
await session.importArchive(
  'client-jar',
  clientJar.name,
  await clientJar.arrayBuffer(),
)
```

`dependencies` 只声明数据包和资源包，不能用它隐式加载 client JAR。Worker 只提取 JAR 中的 `assets/` 条目，不执行 class 文件。完整类型与预算见 [Playground API 参考](/reference/playground-api#客户端资源导入)。

### 共享世界与实时视窗

同页组件可使用相同的 `sandbox-id` 共享一个串行 Worker 会话：

```vue
<DpsCell v-model="builder" sandbox-id="tutorial-world" />
<DpsCell v-model="inspector" sandbox-id="tutorial-world" :viewport="true" />
```

每个编辑器保留独立源码、诊断和输出；命令、导入、检查点、重置与视窗作用于同一世界。也可显式创建 `PlaygroundSessionController`，再连接 `DpsPlayground`、`DpsCell` 或独立的 `DpsViewport`。实时画面走延迟加载的 WebGL2 chunk，静态 PNG 和 GIF 仍由共享软件渲染器生成。

### 纯静态部署

```bash
npm ci
npm run docs:build
```

部署 VitePress 生成目录即可。带内容 hash 的 Worker/profile 资源适合长期 immutable 缓存，HTML 保持常规重新验证。

## 组件 API

本节原有的 props、events、共享 session 和错误码清单已迁移到 [Playground API 参考](/reference/playground-api)。这里保留标题，确保旧的章节链接仍能找到新的权威入口。

## Worker 协议

Worker 请求、事件、transferable 二进制响应、生命周期和稳定错误码也统一维护在 [Playground API 参考](/reference/playground-api#worker-协议)。

## 限制

- 默认预算用于维持浏览器稳定，并不是不可信代码的安全隔离边界。
- 执行只在 MCFunction 命令边界协作中断；已完成命令对世界的修改不会回滚。
- watchdog 超时会终止 Worker，并以 `SESSION_LOST` 结束在途请求；也不会假装恢复了旧世界。
- 渲染结果是确定性的 clean-room 近似画面，`visualParity` 为 `false`，不承诺与原版客户端像素一致。
- 不导入 client JAR 时，Web renderer 只使用内置 fallback；它不会根据 notebook `version` 自动取得客户端资源。
- 自定义 `worker-url` 必须同源，或由服务端提供 module Worker 所需的跨域响应头。

## 相关页面

- [Playground API 参考](/reference/playground-api)
- [Playground CSS 样式定制](/guide/playground-styling)
- [渲染、动图与实时视窗](/guide/rendering-notebook)
- [Serve JSONL 协议](/reference/serve-jsonl)
