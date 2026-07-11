<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()

const copy = computed(() => lang.value.startsWith('en') ? {
  eyebrow: 'ONE RUNTIME · THREE WORKFLOWS',
  title: 'Move from an idea to a reproducible test',
  body: 'Use the same deterministic runtime from the terminal, a manifest suite, or your JVM tests. Reports share the same outputs, traces, diagnostics, and snapshots.',
  steps: [
    ['01', 'Load', 'Read packs and version-specific resource formats.'],
    ['02', 'Run', 'Execute commands, functions, ticks, and player events.'],
    ['03', 'Assert', 'Compare observable output and world state.'],
  ],
  terminalLabel: 'manifest regression',
  result: 'ALL CHECKS PASSED',
  metrics: [
    ['4', 'commands'],
    ['2', 'outputs'],
    ['7', 'state changes'],
  ],
  boundaryEyebrow: 'DESIGNED FOR TRUST',
  boundaryTitle: 'Fast feedback without pretending to be a server',
  boundaryBody: 'Datapack Sandbox is intentionally explicit about what it models. Use it for deterministic development feedback, then reserve vanilla integration runs for behavior that genuinely requires the game engine.',
  boundaryItems: [
    ['Deterministic', 'Stable seeds, snapshots, and structured reports make failures reproducible.'],
    ['Inspectable', 'Every command can expose source locations, output events, traces, and state diffs.'],
    ['Version-aware', 'Built-in profiles cover modern datapack formats from 1.20.4 through 26.2.'],
  ],
} : {
  eyebrow: '一个运行时 · 三种工作流',
  title: '把灵感快速变成可重复的测试',
  body: '无论使用终端、manifest 测试集还是 JVM 测试，都运行在同一套确定性沙盒上，并共享输出、trace、诊断和 snapshot 报告。',
  steps: [
    ['01', '加载', '读取数据包和对应版本的资源格式。'],
    ['02', '执行', '运行命令、函数、tick 与玩家事件。'],
    ['03', '断言', '比较可观察输出和世界状态。'],
  ],
  terminalLabel: 'manifest 回归检查',
  result: '全部检查通过',
  metrics: [
    ['4', '条命令'],
    ['2', '个输出'],
    ['7', '处状态变化'],
  ],
  boundaryEyebrow: '为可信反馈而设计',
  boundaryTitle: '追求快速反馈，但不假装自己是原版服务器',
  boundaryBody: 'Datapack Sandbox 会明确标注模拟边界。适合用它获得确定性的开发反馈；确实依赖游戏引擎的行为，则应保留原版集成测试。',
  boundaryItems: [
    ['可重复', '稳定的随机种子、snapshot 与结构化报告，让失败可以可靠复现。'],
    ['可观测', '命令可以记录源码位置、输出事件、执行 trace 与状态差异。'],
    ['版本感知', '内置 profile 覆盖从 1.20.4 到 26.2 的现代数据包格式。'],
  ],
})
</script>

<template>
  <div class="home-workbench">
    <section class="home-workbench__flow">
      <div class="home-workbench__intro">
        <p class="home-eyebrow">{{ copy.eyebrow }}</p>
        <h2>{{ copy.title }}</h2>
        <p>{{ copy.body }}</p>

        <ol class="workflow-steps">
          <li v-for="step in copy.steps" :key="step[0]">
            <span class="workflow-steps__index">{{ step[0] }}</span>
            <div>
              <strong>{{ step[1] }}</strong>
              <p>{{ step[2] }}</p>
            </div>
          </li>
        </ol>
      </div>

      <article class="terminal-card" aria-label="Datapack Sandbox terminal example">
        <header class="terminal-card__bar">
          <div class="terminal-card__lights" aria-hidden="true"><i/><i/><i/></div>
          <span>{{ copy.terminalLabel }}</span>
          <span class="terminal-card__version">26.2</span>
        </header>
        <div class="terminal-card__body">
          <p><span class="terminal-card__prompt">$</span> dps check --strict examples</p>
          <p class="terminal-card__muted">loading 7 manifests · profile 26.2</p>
          <p><span class="terminal-card__pass">PASS</span> generator-output.dps.json</p>
          <p><span class="terminal-card__pass">PASS</span> player-events.dps.json</p>
          <p><span class="terminal-card__pass">PASS</span> full-stack.dps.json</p>
          <div class="terminal-card__result">
            <span>{{ copy.result }}</span>
            <div class="terminal-card__metrics">
              <span v-for="metric in copy.metrics" :key="metric[1]"><strong>{{ metric[0] }}</strong>{{ metric[1] }}</span>
            </div>
          </div>
        </div>
      </article>
    </section>

    <section class="home-boundary">
      <div class="home-boundary__copy">
        <p class="home-eyebrow">{{ copy.boundaryEyebrow }}</p>
        <h2>{{ copy.boundaryTitle }}</h2>
        <p>{{ copy.boundaryBody }}</p>
      </div>
      <div class="home-boundary__grid">
        <article v-for="(item, index) in copy.boundaryItems" :key="item[0]">
          <span>0{{ index + 1 }}</span>
          <h3>{{ item[0] }}</h3>
          <p>{{ item[1] }}</p>
        </article>
      </div>
    </section>
  </div>
</template>
