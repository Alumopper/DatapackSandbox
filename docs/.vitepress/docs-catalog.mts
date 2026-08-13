import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

export type DocsLocale = 'zh' | 'en'
export type DocsGroup = 'start' | 'workflows' | 'integrations' | 'reference' | 'help'

export interface DocsCatalogEntry {
  id: string
  source: string
  route: string
  group: DocsGroup
  title: Record<DocsLocale, string>
  home?: boolean
}

export const docsCatalog: DocsCatalogEntry[] = [
  { id: 'home', source: 'index', route: '/', group: 'start', title: { zh: '文档首页', en: 'Overview' }, home: true },
  { id: 'getting-started', source: 'getting-started', route: '/guide/getting-started', group: 'start', title: { zh: '快速开始', en: 'Getting Started' } },
  { id: 'installation', source: 'installation', route: '/workflows/installation', group: 'start', title: { zh: '安装与获取', en: 'Install and Obtain' } },

  { id: 'cli-workflow', source: 'cli-workflow', route: '/workflows/cli', group: 'workflows', title: { zh: '使用 CLI 运行', en: 'Run with the CLI' } },
  { id: 'repl-workflow', source: 'repl-workflow', route: '/workflows/repl', group: 'workflows', title: { zh: '使用 REPL 调试', en: 'Debug with the REPL' } },
  { id: 'manifest-workflow', source: 'manifest-workflow', route: '/workflows/manifest-tests', group: 'workflows', title: { zh: 'Manifest 回归测试', en: 'Manifest Regression Tests' } },
  { id: 'ci-coverage', source: 'ci-coverage', route: '/workflows/ci-coverage', group: 'workflows', title: { zh: 'CI 与覆盖率', en: 'CI and Coverage' } },
  { id: 'testing-patterns', source: 'testing-patterns', route: '/guide/testing-patterns', group: 'workflows', title: { zh: '测试模式', en: 'Testing Patterns' } },
  { id: 'player-events', source: 'player-events', route: '/runtime/player-events', group: 'workflows', title: { zh: '玩家事件', en: 'Player Events' } },

  { id: 'vscode-extension', source: 'vscode-extension', route: '/guide/vscode-extension', group: 'integrations', title: { zh: 'VS Code 扩展', en: 'VS Code Extension' } },
  { id: 'playground', source: 'playground', route: '/guide/playground', group: 'integrations', title: { zh: 'Playground', en: 'Playground' } },
  { id: 'playground-styling', source: 'playground-styling', route: '/guide/playground-styling', group: 'integrations', title: { zh: 'Playground 样式', en: 'Playground Styling' } },
  { id: 'rendering-notebook', source: 'rendering-notebook', route: '/guide/rendering-notebook', group: 'integrations', title: { zh: '渲染与实时视窗', en: 'Rendering and Live Viewports' } },
  { id: 'jupyter', source: 'jupyter', route: '/integrations/jupyter', group: 'integrations', title: { zh: 'Jupyter', en: 'Jupyter' } },

  { id: 'cli-reference', source: 'cli-reference', route: '/reference/cli', group: 'reference', title: { zh: 'CLI 参考', en: 'CLI Reference' } },
  { id: 'manifest-reference', source: 'manifest-reference', route: '/reference/manifest', group: 'reference', title: { zh: 'Manifest 参考', en: 'Manifest Reference' } },
  { id: 'code-test-api', source: 'code-test-api', route: '/guide/code-test-api', group: 'reference', title: { zh: 'QuickTest 总览', en: 'QuickTest Overview' } },
  { id: 'quicktest-fixtures', source: 'quicktest-fixtures', route: '/reference/quicktest-fixtures', group: 'reference', title: { zh: 'QuickTest Fixture', en: 'QuickTest Fixtures' } },
  { id: 'quicktest-assertions', source: 'quicktest-assertions', route: '/reference/quicktest-assertions', group: 'reference', title: { zh: 'QuickTest 断言', en: 'QuickTest Assertions' } },
  { id: 'core-api', source: 'core-api', route: '/reference/core-api', group: 'reference', title: { zh: 'Core API', en: 'Core API' } },
  { id: 'renderer-api', source: 'renderer-api', route: '/reference/renderer-api', group: 'reference', title: { zh: 'Renderer API', en: 'Renderer API' } },
  { id: 'serve-jsonl', source: 'serve-jsonl', route: '/reference/serve-jsonl', group: 'reference', title: { zh: 'Serve JSONL 协议', en: 'Serve JSONL Protocol' } },
  { id: 'playground-api', source: 'playground-api', route: '/reference/playground-api', group: 'reference', title: { zh: 'Playground API', en: 'Playground API' } },
  { id: 'reports-observability', source: 'reports-observability', route: '/reference/reports-observability', group: 'reference', title: { zh: '报告与可观测性', en: 'Reports and Observability' } },
  { id: 'command-support', source: 'command-support', route: '/runtime/command-support', group: 'reference', title: { zh: '命令支持', en: 'Command Support' } },
  { id: 'resource-formats', source: 'resource-formats', route: '/resources/resource-formats', group: 'reference', title: { zh: '资源格式', en: 'Resource Formats' } },
  { id: 'version-profile', source: 'version-profile', route: '/resources/version-profile', group: 'reference', title: { zh: '版本 Profile', en: 'Version Profiles' } },
  { id: 'runtime-world', source: 'runtime-world', route: '/runtime/world-model', group: 'reference', title: { zh: '世界模型', en: 'World Model' } },

  { id: 'troubleshooting', source: 'troubleshooting', route: '/guide/troubleshooting', group: 'help', title: { zh: '排障手册', en: 'Troubleshooting' } },
  { id: 'development-roadmap', source: 'development-roadmap', route: '/project/development-roadmap', group: 'help', title: { zh: '开发路线图', en: 'Development Roadmap' } },
]

const groupTitles: Record<DocsGroup, Record<DocsLocale, string>> = {
  start: { zh: '开始', en: 'Start' },
  workflows: { zh: '工作流', en: 'Workflows' },
  integrations: { zh: '工具与集成', en: 'Tools and Integrations' },
  reference: { zh: '参考', en: 'Reference' },
  help: { zh: '帮助与项目', en: 'Help and Project' },
}

export function localizedRoute(entry: DocsCatalogEntry, locale: DocsLocale): string {
  if (locale === 'zh') return entry.route
  return entry.route === '/' ? '/en/' : `/en${entry.route}`
}

// VitePress sidebar groups use text/items/collapsed as documented at:
// https://vitepress.dev/reference/default-theme-sidebar#collapsible-sidebar-groups
export function createSidebar(locale: DocsLocale) {
  return (Object.keys(groupTitles) as DocsGroup[]).map((group) => ({
    text: groupTitles[group][locale],
    collapsed: group === 'help',
    items: docsCatalog
      .filter((entry) => entry.group === group)
      .map((entry) => ({ text: entry.title[locale], link: localizedRoute(entry, locale) })),
  }))
}

export function createNav(locale: DocsLocale) {
  const route = (id: string) => localizedRoute(docsCatalog.find((entry) => entry.id === id)!, locale)
  return [
    { text: locale === 'zh' ? '首页' : 'Home', link: route('home') },
    {
      text: locale === 'zh' ? '工作流' : 'Workflows',
      activeMatch: locale === 'zh' ? '^/(workflows|guide/testing-patterns|runtime/player-events)' : '^/en/(workflows|guide/testing-patterns|runtime/player-events)',
      items: ['cli-workflow', 'repl-workflow', 'manifest-workflow', 'ci-coverage', 'testing-patterns', 'player-events']
        .map((id) => docsCatalog.find((entry) => entry.id === id)!)
        .map((entry) => ({ text: entry.title[locale], link: localizedRoute(entry, locale) })),
    },
    {
      text: locale === 'zh' ? '工具与集成' : 'Integrations',
      activeMatch: locale === 'zh' ? '^/(integrations|guide/(vscode-extension|playground|playground-styling|rendering-notebook))' : '^/en/(integrations|guide/(vscode-extension|playground|playground-styling|rendering-notebook))',
      items: docsCatalog
        .filter((entry) => entry.group === 'integrations')
        .map((entry) => ({ text: entry.title[locale], link: localizedRoute(entry, locale) })),
    },
    {
      text: locale === 'zh' ? '参考' : 'Reference',
      activeMatch: locale === 'zh' ? '^/(reference|runtime|resources|guide/code-test-api)' : '^/en/(reference|runtime|resources|guide/code-test-api)',
      items: docsCatalog
        .filter((entry) => entry.group === 'reference')
        .map((entry) => ({ text: entry.title[locale], link: localizedRoute(entry, locale) })),
    },
  ]
}

// VitePress rewrites map source paths to public routes:
// https://vitepress.dev/reference/site-config#rewrites
export const docsRewrites = Object.fromEntries(
  docsCatalog.flatMap((entry) => {
    if (entry.home) return []
    return [
      [`${entry.source}.zh-CN.md`, `${entry.route.slice(1)}.md`],
      [`${entry.source}.md`, `en${entry.route}.md`],
    ]
  }),
)

export function validateDocsCatalog(docsDirectory: string): void {
  const ids = new Set<string>()
  const routes = new Set<string>()
  const expectedRootMarkdown = new Set<string>(['index.md'])

  for (const entry of docsCatalog) {
    if (ids.has(entry.id)) throw new Error(`Duplicate documentation id: ${entry.id}`)
    if (routes.has(entry.route)) throw new Error(`Duplicate documentation route: ${entry.route}`)
    ids.add(entry.id)
    routes.add(entry.route)

    const sources = entry.home
      ? ['index.md', 'en/index.md']
      : [`${entry.source}.zh-CN.md`, `${entry.source}.md`]
    for (const source of sources) {
      if (!existsSync(join(docsDirectory, source))) {
        throw new Error(`Documentation catalog entry '${entry.id}' is missing ${source}`)
      }
    }
    if (!entry.home) {
      expectedRootMarkdown.add(`${entry.source}.zh-CN.md`)
      expectedRootMarkdown.add(`${entry.source}.md`)
    }
  }

  const actualRootMarkdown = readdirSync(docsDirectory)
    .filter((name) => name.endsWith('.md'))
  const orphaned = actualRootMarkdown.filter((name) => !expectedRootMarkdown.has(name))
  if (orphaned.length > 0) throw new Error(`Root Markdown files missing from docs catalog: ${orphaned.join(', ')}`)
}
