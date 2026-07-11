import { defineConfigWithTheme } from 'vitepress'
import type { ThemeConfig } from 'vitepress-carbon'
import baseConfig from 'vitepress-carbon/config'

const repository = 'https://github.com/Alumopper/DatapackSandbox'
const docsBase = (() => {
  const raw = process.env.DOCS_BASE?.trim() || '/'
  const withLeadingSlash = raw.startsWith('/') ? raw : `/${raw}`
  return withLeadingSlash.endsWith('/') ? withLeadingSlash : `${withLeadingSlash}/`
})()

const zhSidebar = [
  {
    text: '开始',
    collapsed: false,
    items: [
      { text: '文档首页', link: '/' },
      { text: '快速开始', link: '/guide/getting-started' },
      { text: '测试方案', link: '/guide/testing-patterns' },
      { text: '代码测试 API', link: '/guide/code-test-api' },
      { text: '排障手册', link: '/guide/troubleshooting' },
    ],
  },
  {
    text: '运行时',
    collapsed: false,
    items: [
      { text: '命令支持状态', link: '/runtime/command-support' },
      { text: '玩家事件', link: '/runtime/player-events' },
      { text: '运行时世界模型', link: '/runtime/world-model' },
    ],
  },
  {
    text: '资源与版本',
    collapsed: false,
    items: [
      { text: '资源格式', link: '/resources/resource-formats' },
      { text: '版本 Profile', link: '/resources/version-profile' },
    ],
  },
  {
    text: '项目',
    collapsed: true,
    items: [
      { text: '开发路线图', link: '/project/development-roadmap' },
    ],
  },
]

const enSidebar = [
  {
    text: 'Start',
    collapsed: false,
    items: [
      { text: 'Overview', link: '/en/' },
      { text: 'Getting Started', link: '/en/guide/getting-started' },
      { text: 'Testing Patterns', link: '/en/guide/testing-patterns' },
      { text: 'Code Test API', link: '/en/guide/code-test-api' },
      { text: 'Troubleshooting', link: '/en/guide/troubleshooting' },
    ],
  },
  {
    text: 'Runtime',
    collapsed: false,
    items: [
      { text: 'Command Support', link: '/en/runtime/command-support' },
      { text: 'Player Events', link: '/en/runtime/player-events' },
      { text: 'Runtime World Model', link: '/en/runtime/world-model' },
    ],
  },
  {
    text: 'Resources and Versions',
    collapsed: false,
    items: [
      { text: 'Resource Formats', link: '/en/resources/resource-formats' },
      { text: 'Version Profiles', link: '/en/resources/version-profile' },
    ],
  },
  {
    text: 'Project',
    collapsed: true,
    items: [
      { text: 'Development Roadmap', link: '/en/project/development-roadmap' },
    ],
  },
]

const zhNav = [
  { text: '首页', link: '/' },
  {
    text: '使用指南',
    items: [
      { text: '开发者入门', link: '/guide/getting-started' },
      { text: '测试模式', link: '/guide/testing-patterns' },
      { text: '代码测试 API', link: '/guide/code-test-api' },
      { text: '排障手册', link: '/guide/troubleshooting' },
      { text: '玩家事件', link: '/runtime/player-events' },
      { text: '运行时世界模型', link: '/runtime/world-model' },
    ],
  },
  {
    text: '技术参考',
    items: [
      { text: '命令支持状态', link: '/runtime/command-support' },
      { text: '资源格式', link: '/resources/resource-formats' },
      { text: '版本 Profile', link: '/resources/version-profile' },
      { text: '开发路线图', link: '/project/development-roadmap' },
    ],
  },
]

const enNav = [
  { text: 'Home', link: '/en/' },
  {
    text: 'Guide',
    items: [
      { text: 'Getting Started', link: '/en/guide/getting-started' },
      { text: 'Testing Patterns', link: '/en/guide/testing-patterns' },
      { text: 'Code Test API', link: '/en/guide/code-test-api' },
      { text: 'Troubleshooting', link: '/en/guide/troubleshooting' },
      { text: 'Player Events', link: '/en/runtime/player-events' },
      { text: 'Runtime World Model', link: '/en/runtime/world-model' },
    ],
  },
  {
    text: 'Reference',
    items: [
      { text: 'Command Support', link: '/en/runtime/command-support' },
      { text: 'Resource Formats', link: '/en/resources/resource-formats' },
      { text: 'Version Profiles', link: '/en/resources/version-profile' },
      { text: 'Development Roadmap', link: '/en/project/development-roadmap' },
    ],
  },
]

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
  extends: baseConfig,
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
  },
  rewrites: {
    'getting-started.zh-CN.md': 'guide/getting-started.md',
    'testing-patterns.zh-CN.md': 'guide/testing-patterns.md',
    'code-test-api.zh-CN.md': 'guide/code-test-api.md',
    'troubleshooting.zh-CN.md': 'guide/troubleshooting.md',
    'command-support.zh-CN.md': 'runtime/command-support.md',
    'player-events.zh-CN.md': 'runtime/player-events.md',
    'runtime-world.zh-CN.md': 'runtime/world-model.md',
    'resource-formats.zh-CN.md': 'resources/resource-formats.md',
    'version-profile.zh-CN.md': 'resources/version-profile.md',
    'development-roadmap.zh-CN.md': 'project/development-roadmap.md',
    'getting-started.md': 'en/guide/getting-started.md',
    'testing-patterns.md': 'en/guide/testing-patterns.md',
    'code-test-api.md': 'en/guide/code-test-api.md',
    'troubleshooting.md': 'en/guide/troubleshooting.md',
    'command-support.md': 'en/runtime/command-support.md',
    'player-events.md': 'en/runtime/player-events.md',
    'runtime-world.md': 'en/runtime/world-model.md',
    'resource-formats.md': 'en/resources/resource-formats.md',
    'version-profile.md': 'en/resources/version-profile.md',
    'development-roadmap.md': 'en/project/development-roadmap.md',
  },
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
