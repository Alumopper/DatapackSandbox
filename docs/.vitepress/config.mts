import { defineConfigWithTheme } from 'vitepress'
import type { ThemeConfig } from 'vitepress-carbon'
import baseConfig from 'vitepress-carbon/config'
import { fileURLToPath } from 'node:url'
import { createNav, createSidebar, docsRewrites, validateDocsCatalog } from './docs-catalog.mts'

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url))
const docsDirectory = fileURLToPath(new URL('..', import.meta.url))
validateDocsCatalog(docsDirectory)

const repository = 'https://github.com/Alumopper/DatapackSandbox'
const docsBase = (() => {
  const raw = process.env.DOCS_BASE?.trim() || '/'
  const withLeadingSlash = raw.startsWith('/') ? raw : `/${raw}`
  return withLeadingSlash.endsWith('/') ? withLeadingSlash : `${withLeadingSlash}/`
})()

const zhSidebar = createSidebar('zh')
const enSidebar = createSidebar('en')
const zhNav = createNav('zh')
const enNav = createNav('en')

const commonThemeConfig = {
  logo: '/datapack-sandbox-mark.svg',
  siteTitle: 'Datapack Sandbox',
  socialLinks: [
    { icon: 'github', link: repository },
  ],
  externalLinkIcon: true,
} satisfies ThemeConfig

const zhThemeConfig = {
  ...commonThemeConfig,
  footer: {
    message: '在本地复现数据包行为，在提交前发现问题。',
    copyright: 'Datapack Sandbox · Clean-room runtime · Open source.',
  },
  nav: zhNav,
  sidebar: zhSidebar,
  search: {
    provider: 'local',
    options: {
      translations: {
        button: {
          buttonText: '搜索文档',
          buttonAriaLabel: '搜索文档',
        },
        modal: {
          displayDetails: '显示详情',
          resetButtonTitle: '清除搜索',
          backButtonTitle: '关闭搜索',
          noResultsText: '没有找到结果',
          footer: {
            selectText: '选择',
            navigateText: '切换',
            closeText: '关闭',
          },
        },
      },
    },
  },
  outline: {
    level: [2, 3],
    label: '本页目录',
  },
  lastUpdated: {
    text: '最后更新',
    formatOptions: {
      dateStyle: 'medium',
      timeStyle: 'short',
    },
  },
  editLink: {
    pattern: `${repository}/edit/master/docs/:path`,
    text: '编辑此页',
  },
  docFooter: {
    prev: '上一页',
    next: '下一页',
  },
  darkModeSwitchLabel: '外观',
  lightModeSwitchTitle: '切换到浅色模式',
  darkModeSwitchTitle: '切换到深色模式',
  sidebarMenuLabel: '菜单',
  returnToTopLabel: '回到顶部',
  langMenuLabel: '切换语言',
  notFound: {
    title: '页面不存在',
    quote: '这个页面还没有被文档索引。',
    linkLabel: '返回首页',
    linkText: '返回首页',
  },
} satisfies ThemeConfig

const enThemeConfig = {
  ...commonThemeConfig,
  footer: {
    message: 'Reproduce datapack behavior locally. Find failures before you ship.',
    copyright: 'Datapack Sandbox · Clean-room runtime · Open source.',
  },
  nav: enNav,
  sidebar: enSidebar,
  search: {
    provider: 'local',
    options: {
      translations: {
        button: {
          buttonText: 'Search docs',
          buttonAriaLabel: 'Search docs',
        },
        modal: {
          displayDetails: 'Display detailed list',
          resetButtonTitle: 'Reset search',
          backButtonTitle: 'Close search',
          noResultsText: 'No results found',
          footer: {
            selectText: 'to select',
            navigateText: 'to navigate',
            closeText: 'to close',
          },
        },
      },
    },
  },
  outline: {
    level: [2, 3],
    label: 'On this page',
  },
  lastUpdated: {
    text: 'Last updated',
    formatOptions: {
      dateStyle: 'medium',
      timeStyle: 'short',
    },
  },
  editLink: {
    pattern: `${repository}/edit/master/docs/:path`,
    text: 'Edit this page',
  },
  docFooter: {
    prev: 'Previous page',
    next: 'Next page',
  },
  darkModeSwitchLabel: 'Appearance',
  lightModeSwitchTitle: 'Switch to light theme',
  darkModeSwitchTitle: 'Switch to dark theme',
  sidebarMenuLabel: 'Menu',
  returnToTopLabel: 'Return to top',
  langMenuLabel: 'Change language',
  notFound: {
    title: 'Page not found',
    quote: 'This page is not indexed in the documentation.',
    linkLabel: 'Go to home',
    linkText: 'Go to home',
  },
} satisfies ThemeConfig

export default defineConfigWithTheme<ThemeConfig>({
  ...baseConfig,
  vite: {
    ...baseConfig.vite,
    optimizeDeps: {
      ...baseConfig.vite?.optimizeDeps,
      // Keep the packaged Worker URL relative to the package's dist module.
      // Vite's dep optimizer relocates import.meta.url without its Worker.
      exclude: [
        ...(baseConfig.vite?.optimizeDeps?.exclude ?? []),
        '@datapack-sandbox/vitepress-playground',
      ],
    },
    plugins: (baseConfig.vite?.plugins ?? []).filter((entry) => {
      const plugins = Array.isArray(entry) ? entry : [entry]
      return !plugins.some(
        (plugin) =>
          plugin &&
          typeof plugin === 'object' &&
          'name' in plugin &&
          String(plugin.name).startsWith('vitepress-plugin-llms'),
      )
    }),
    server: {
      fs: {
        // The playground is a workspace package whose built, self-contained
        // Worker is loaded from packages/ while VitePress serves docs/.
        allow: [repositoryRoot],
      },
    },
  },
  title: 'Datapack Sandbox',
  description: 'Clean-room Minecraft Java datapack sandbox documentation.',
  lang: 'zh-CN',
  base: docsBase,
  appearance: 'dark',
  cleanUrls: false,
  lastUpdated: true,
  markdown: {
    html: false,
    lineNumbers: true,
    headers: {
      level: [2, 3],
    },
    config(md) {
      md.block.ruler.before('paragraph', 'playground-demo', (state, startLine, _endLine, silent) => {
        const start = state.bMarks[startLine] + state.tShift[startLine]
        const end = state.eMarks[startLine]
        if (state.src.slice(start, end).trim() !== '[[playground-demo]]') return false
        if (silent) return true

        const token = state.push('playground_demo', '', 0)
        token.map = [startLine, startLine + 1]
        state.line = startLine + 1
        return true
      })
      md.renderer.rules.playground_demo = () => '<ClientOnly><PlaygroundDemo /></ClientOnly>\n'
      md.block.ruler.before('paragraph', 'cell-demo', (state, startLine, _endLine, silent) => {
        const start = state.bMarks[startLine] + state.tShift[startLine]
        const end = state.eMarks[startLine]
        if (state.src.slice(start, end).trim() !== '[[cell-demo]]') return false
        if (silent) return true

        const token = state.push('cell_demo', '', 0)
        token.map = [startLine, startLine + 1]
        state.line = startLine + 1
        return true
      })
      md.renderer.rules.cell_demo = () => '<ClientOnly><CellDemo /></ClientOnly>\n'
    },
  },
  rewrites: docsRewrites,
  head: [
    ['link', { rel: 'icon', href: `${docsBase}datapack-sandbox-mark.svg`, type: 'image/svg+xml' }],
    ['meta', { name: 'theme-color', content: '#07120f' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Datapack Sandbox' }],
    ['meta', { property: 'og:description', content: 'Test Minecraft Java datapacks without starting a server.' }],
    ['meta', { property: 'og:image', content: `${docsBase}datapack-sandbox-og.svg` }],
  ],
  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/',
      title: 'Datapack Sandbox',
      description: '面向 Minecraft Java 数据包的本地测试和调试沙盒文档。',
      themeConfig: zhThemeConfig,
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      title: 'Datapack Sandbox',
      description: 'Documentation for a clean-room Minecraft Java datapack sandbox.',
      themeConfig: enThemeConfig,
    },
  },
  themeConfig: zhThemeConfig,
})
