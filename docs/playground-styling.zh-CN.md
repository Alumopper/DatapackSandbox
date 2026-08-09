# Playground CSS 样式定制

`@datapack-sandbox/vitepress-playground` 自带完整默认样式，同时允许网站开发者调整颜色、字体、边框、阴影、间距和指定内部区域。优先使用组件根节点上的 `--dps-*` CSS 自定义属性；只有需要修改具体布局时，再使用结构 class。

本页同时适用于 `DpsPlayground` 和 `DpsCell`。两个组件都只渲染一个 `.dps-playground` 根节点，并会把调用方传入的 `class` 或 `style` 合并到该节点。

## 在包样式之后加载覆盖样式

VitePress 网站可在主题入口中先导入包样式，再导入网站自己的覆盖文件：

```ts
// docs/.vitepress/theme/index.ts
import DefaultTheme from 'vitepress/theme'
import '@datapack-sandbox/vitepress-playground/style.css'
import './playground-theme.css'

export default DefaultTheme
```

VitePress 官方使用主题入口加载自定义 CSS。让覆盖文件排在后面，可以使相同优先级的规则由网站样式胜出。参见 [扩展默认主题](https://vitepress.dev/zh/guide/extending-default-theme#自定义-css)。

如果组件由本地 Vue 包装组件注册，包样式仍应作为全局 CSS 导入，不要把包样式的 `import` 放进 scoped style。

## 给实例添加网站专用 class

为实例传入专用 class，可以避免网站主题误伤页面上的其他沙盒：

```vue
<DpsPlayground
  class="docs-sandbox"
  :notebook="notebook"
  theme="auto"
/>

<DpsCell
  v-model="source"
  class="docs-sandbox docs-sandbox--small"
  version="26.2"
/>
```

Vue 会把单根组件上的 `class`、`style` 透传到根元素，并与组件已有 class 合并。参见 Vue 的[透传 Attributes](https://cn.vuejs.org/guide/components/attrs.html#class-与-style-的合并)。

## 可直接复制的品牌主题

组件内置的显式浅色/深色规则以及自动深色规则，比单独的 `.dps-playground` 选择器优先级更高。下面的写法会匹配当前主题状态，因此无需使用 `!important`，也不会因切换 `theme` prop 而丢失覆盖结果。

```css
/* docs/.vitepress/theme/playground-theme.css */
.dps-playground.docs-sandbox,
.dps-playground.docs-sandbox:is(.dps-theme-light, .dps-theme-dark),
.dark .dps-playground.docs-sandbox.dps-theme-auto {
  --dps-accent: #7c5cff;
  --dps-accent-hover: #9278ff;
  --dps-accent-soft: color-mix(in srgb, #7c5cff 18%, transparent);
  --dps-on-accent: #ffffff;
  --dps-border-strong: color-mix(in srgb, var(--dps-accent) 38%, var(--dps-border));
  --dps-selection: color-mix(in srgb, var(--dps-accent) 26%, transparent);

  --vp-font-family-base: Inter, system-ui, sans-serif;
  --vp-font-family-mono: "JetBrains Mono", ui-monospace, monospace;

  border-radius: 12px;
}

html:not(.dark) .dps-playground.docs-sandbox.dps-theme-auto,
.dps-playground.docs-sandbox.dps-theme-light {
  --dps-bg: #f6f5ff;
  --dps-panel: #ffffff;
  --dps-panel-soft: #f0eeff;
  --dps-code-bg: #fbfaff;
  --dps-border: #d9d4f0;
  --dps-text: #201b33;
  --dps-muted: #655f78;
  --dps-faint: #8c86a0;
}

.dark .dps-playground.docs-sandbox.dps-theme-auto,
.dps-playground.docs-sandbox.dps-theme-dark {
  --dps-bg: #12101b;
  --dps-panel: #191625;
  --dps-panel-soft: #211d30;
  --dps-code-bg: #15121f;
  --dps-border: #37314a;
  --dps-text: #f4f0ff;
  --dps-muted: #b5accb;
  --dps-faint: #817892;
}

.dps-playground.docs-sandbox .dps-cell {
  border-radius: 9px;
}

@media (max-width: 640px) {
  .dps-playground.docs-sandbox {
    border-radius: 8px;
  }
}
```

如果某个实例只需要不同的强调色，可以直接传入内联变量。内联变量拥有最高的作者样式优先级：

```vue
<DpsCell
  v-model="source"
  :style="{
    '--dps-accent': '#e85d9e',
    '--dps-accent-hover': '#f27db3',
    '--dps-accent-soft': '#3b1c2c',
  }"
/>
```

## 主题变量参考

把以下变量设置在 `.dps-playground`，或附加到组件根节点的网站专用 class 上。

| 分组 | 变量 | 作用范围 |
| --- | --- | --- |
| 表面 | `--dps-bg`、`--dps-panel`、`--dps-panel-soft`、`--dps-code-bg` | 根容器、卡片、次级控件和编辑器背景。 |
| 文字 | `--dps-text`、`--dps-muted`、`--dps-faint` | 主文字、次级文字和弱化文字。 |
| 品牌色 | `--dps-accent`、`--dps-accent-hover`、`--dps-accent-soft`、`--dps-on-accent` | 主操作、焦点、选区强调及主按钮文字。 |
| 错误 | `--dps-danger`、`--dps-danger-soft` | 错误文字、边框和错误背景。 |
| 边框与层次 | `--dps-border`、`--dps-border-strong`、`--dps-shadow`、`--dps-cell-shadow`、`--dps-selection` | 分隔线、焦点边框、根/单元阴影和编辑器选区。 |
| 语法高亮 | `--dps-syntax-keyword`、`--dps-syntax-type`、`--dps-syntax-string`、`--dps-syntax-number`、`--dps-syntax-bool`、`--dps-syntax-comment`、`--dps-syntax-operator` | MCFunction 编辑器语法高亮。 |

默认自动浅色主题会先读取 `--vp-c-bg`、`--vp-c-text-1`、`--vp-c-brand-1`、`--vp-c-divider` 等 VitePress 变量，再使用独立站点 fallback。组件还会读取 `--vp-font-family-base` 和 `--vp-font-family-mono`。因此，已经配置这些变量的 VitePress 网站在添加任何 `--dps-*` 覆盖前，就能获得合理的基础适配。

## 浅色与深色模式

`theme` prop 接受 `auto`、`light` 或 `dark`：

```vue
<DpsPlayground :theme="isDark ? 'dark' : 'light'" :notebook="notebook" />
```

`auto` 会识别祖先节点上的 `.dark` class，这与 VitePress 的外观切换方式一致。如果网站使用 `data-theme="dark"` 等其他标记，应像上例一样把网站主题状态映射到 prop，或在祖先节点同步 `.dark` class。

浅色与深色模式需要不同品牌色时，可把共享变量放进示例中的组合规则，再分别在两个主题块中设置表面和文字颜色。排查级联问题时，可检查渲染后的根节点究竟带有 `dps-theme-auto`、`dps-theme-light` 还是 `dps-theme-dark`。

## 实时视窗颜色

`.dps-viewport` 会单独从 VitePress 默认值初始化 panel、border、text、muted 和 accent 变量，以保证直接挂载 `DpsViewport` 时仍有完整样式。如果视窗也要使用同一套自定义配色，需要显式覆盖该区域：

```css
.dps-playground.docs-sandbox .dps-viewport {
  --dps-panel: #191625;
  --dps-panel-soft: #211d30;
  --dps-border: #37314a;
  --dps-text: #f4f0ff;
  --dps-muted: #b5accb;
  --dps-accent: #9278ff;
}

.dps-playground.docs-sandbox .dps-viewport-stage {
  background: #09070f;
}
```

Canvas 图像属于世界渲染结果；CSS 可以改变其外框、焦点环和周边控件，但不会重新着色 Minecraft 场景本身。

## 结构 class 钩子

主题适配应优先使用自定义属性。只有需要修改布局或指定区域时，再使用以下无 hash class：

| 区域 | 钩子 |
| --- | --- |
| 根节点与状态 | `.dps-playground`、`.dps-theme-auto`、`.dps-theme-light`、`.dps-theme-dark`、`.dps-layout-notebook`、`.dps-layout-compact`、`.dps-is-busy` |
| Notebook 与 cell | `.dps-toolbar`、`.dps-cells`、`.dps-cell`、`.dps-cell-heading`、`.dps-cell-actions`、`.dps-code-editor` |
| 状态反馈 | `.dps-status`、`.dps-error`、`.dps-output`、`.dps-render` |
| 单 cell | `.dps-cell-space`、`.dps-cell-space-compact`、`.dps-cell-code-compact` |
| 实时视窗 | `.dps-viewport`、`.dps-viewport-toolbar`、`.dps-viewport-stage`、`.dps-viewport-canvas`、`.dps-viewport-command` |

不要依赖 `data-v-*`、生成的资源文件名或 Vite chunk hash。也不要直接依赖 CodeMirror 的 `.cm-*` DOM，除非项目固定了依赖版本，并会在包升级时重新验证；这些节点属于编辑器依赖，而不是沙盒的公开样式面。

## Vue 包装组件中的 scoped style

父组件的 scoped CSS 默认不会进入子组件内部。可从包装 class 使用 Vue 的 `:deep()` 伪类：

```vue
<template>
  <section class="sandbox-host">
    <DpsCell v-model="source" version="26.2" />
  </section>
</template>

<style scoped>
.sandbox-host :deep(.dps-playground) {
  --dps-accent: #ff7a45;
  --dps-accent-hover: #ff966f;
}

.sandbox-host :deep(.dps-cell-heading) {
  min-height: 42px;
}
</style>
```

Vue 官方使用 `:deep()` 从 scoped CSS 选择子组件后代。参见 [SFC CSS 功能](https://cn.vuejs.org/api/sfc-css-features.html#深度选择器)。

## 级联排查

覆盖样式没有生效时，按以下顺序检查：

1. 确认包样式先加载，网站覆盖样式后加载。
2. 确认自定义 class 已出现在渲染后的 `.dps-playground` 根节点。
3. 如果规则位于 `<style scoped>`，对组件内部使用 `:deep()`。
4. 匹配当前 `dps-theme-*` 选择器；自动深色模式的内置规则优先级更高。
5. 对局部初始化变量的 `.dps-viewport` 单独覆盖。
6. 修改变量后检查对比度、焦点可见性、禁用状态及两种颜色模式；不要移除组件的焦点环或 reduced-motion 行为。

组件 props、导入、Worker 生命周期和视窗行为详见[交互式 Playground](/guide/playground)。
