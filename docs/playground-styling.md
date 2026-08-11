# Playground CSS customization

`@datapack-sandbox/vitepress-playground` ships complete default styles, but its colors, type, borders, shadows, spacing, and selected internal regions can be adapted to a host site. The preferred customization surface is the set of `--dps-*` custom properties on the component root. Class selectors are available for narrower structural changes.

This page applies to both `DpsPlayground` and `DpsCell`. Both render one `.dps-playground` root and merge a consumer-provided `class` or `style` onto that element.

## Load overrides after the package CSS

For a VitePress site, import the package stylesheet and then the site override from the theme entry:

```ts
// docs/.vitepress/theme/index.ts
import DefaultTheme from 'vitepress/theme'
import '@datapack-sandbox/vitepress-playground/style.css'
import './playground-theme.css'

export default DefaultTheme
```

VitePress documents this theme-entry pattern for custom CSS. Keeping the override last makes equal-specificity rules resolve to the site stylesheet. See [Extending the Default Theme](https://vitepress.dev/guide/extending-default-theme#customizing-css).

If the component is registered from a local Vue wrapper, keep the package CSS global. Do not place the package import inside a scoped style block.

## Add a site-specific root class

Pass a class to each instance so the site theme does not affect unrelated playgrounds:

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

Vue merges `class` and `style` fallthrough attributes with the class already present on a single component root. See Vue's [Fallthrough Attributes](https://vuejs.org/guide/components/attrs#class-and-style-merging).

## Copyable brand theme

The built-in explicit light/dark rules and automatic dark rule are more specific than a plain `.dps-playground` selector. Match the active theme selectors as shown below; this avoids `!important` and keeps the result stable regardless of the `theme` prop.

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

For a one-off instance, inline custom properties have the highest author-level precedence and are useful for a single accent variation:

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

## Theme variable reference

Set these properties on `.dps-playground` or on a site-specific class attached to the component root.

| Group | Variables | Controls |
| --- | --- | --- |
| Surfaces | `--dps-bg`, `--dps-panel`, `--dps-panel-soft`, `--dps-code-bg` | Root, cards, soft controls, and editor backgrounds. |
| Text | `--dps-text`, `--dps-muted`, `--dps-faint` | Primary, secondary, and low-emphasis text. |
| Brand | `--dps-accent`, `--dps-accent-hover`, `--dps-accent-soft`, `--dps-on-accent` | Primary actions, focus state, selection accents, and text on primary buttons. |
| Errors | `--dps-danger`, `--dps-danger-soft` | Error text, borders, and error backgrounds. |
| Borders and depth | `--dps-border`, `--dps-border-strong`, `--dps-shadow`, `--dps-cell-shadow`, `--dps-selection` | Dividers, focus borders, root/cell elevation, and editor selection. |
| Controls | `--dps-action-height` | Fixed primary/action-menu trigger height; defaults to `32px`. |
| Syntax | `--dps-syntax-keyword`, `--dps-syntax-type`, `--dps-syntax-string`, `--dps-syntax-number`, `--dps-syntax-bool`, `--dps-syntax-comment`, `--dps-syntax-operator` | MCFunction editor highlighting. |

The default automatic light theme reads VitePress variables such as `--vp-c-bg`, `--vp-c-text-1`, `--vp-c-brand-1`, and `--vp-c-divider` before using standalone fallbacks. The component also reads `--vp-font-family-base` and `--vp-font-family-mono`. A VitePress site with those variables already configured therefore gets a reasonable baseline before any `--dps-*` override.

## Light and dark mode

The `theme` prop accepts `auto`, `light`, or `dark`:

```vue
<DpsPlayground :theme="isDark ? 'dark' : 'light'" :notebook="notebook" />
```

`auto` recognizes an ancestor `.dark` class, which matches VitePress's appearance implementation. Sites that use a different marker, such as `data-theme="dark"`, should map their theme state to the prop as above or mirror the `dark` class on an ancestor.

When light and dark variants need different brand colors, place shared tokens in the combined rule and surface/token differences in the two variant blocks from the copyable example. Inspect the rendered root for `dps-theme-auto`, `dps-theme-light`, or `dps-theme-dark` when debugging cascade issues.

## Realtime viewport colors

`.dps-viewport` intentionally establishes its own panel, border, text, muted, and accent variables from VitePress defaults. This keeps a directly mounted `DpsViewport` usable without a notebook wrapper. Override that region explicitly when it must follow the same custom palette:

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

The canvas image itself is rendered world output; CSS can change its box, focus ring, and surrounding controls but not recolor the rendered Minecraft scene.

## Structural class hooks

Prefer custom properties for theming. Use these non-hashed classes only when a layout or a specific region needs an override:

| Area | Hooks |
| --- | --- |
| Root and state | `.dps-playground`, `.dps-theme-auto`, `.dps-theme-light`, `.dps-theme-dark`, `.dps-layout-notebook`, `.dps-layout-compact`, `.dps-is-busy` |
| Notebook and cell | `.dps-toolbar`, `.dps-cells`, `.dps-cell`, `.dps-cell-heading`, `.dps-cell-actions`, `.dps-code-editor` |
| Feedback | `.dps-status`, `.dps-error`, `.dps-output`, `.dps-render` |
| Single cell | `.dps-cell-space`, `.dps-cell-space-compact`, `.dps-cell-code-compact` |
| Viewport | `.dps-viewport`, `.dps-viewport-toolbar`, `.dps-viewport-stage`, `.dps-viewport-canvas`, `.dps-viewport-command` |

Avoid selectors based on `data-v-*`, generated asset names, or Vite chunk hashes. Also avoid depending directly on CodeMirror's `.cm-*` DOM unless the override is pinned and retested when the package is upgraded; those nodes belong to the editor dependency rather than the playground's public styling surface.

## Scoped styles in a Vue wrapper

A parent's scoped CSS does not normally reach child component internals. Use Vue's `:deep()` pseudo-class from a wrapper class:

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

Vue documents `:deep()` for selecting descendants of child components from scoped CSS. See [SFC CSS Features](https://vuejs.org/api/sfc-css-features.html#deep-selectors).

## Cascade troubleshooting

If an override does not appear:

1. Confirm the package stylesheet loads before the site stylesheet.
2. Confirm the custom class is present on the rendered `.dps-playground` root.
3. If the rule is in `<style scoped>`, use `:deep()` for child internals.
4. Match the active `dps-theme-*` selector; automatic dark mode has a more specific built-in rule.
5. Target `.dps-viewport` separately for its locally initialized variables.
6. Check contrast, focus visibility, disabled state, and both color modes after changing tokens. Avoid removing the component's focus outlines or reduced-motion behavior.

Continue with the [Interactive playground guide](/en/guide/playground) for component props, imports, Worker lifecycle, and viewport behavior.
