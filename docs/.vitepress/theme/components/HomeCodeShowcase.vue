<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()

const text = computed(() => {
  if (lang.value.startsWith('en')) {
    return {
      tag: 'Datapack Sandbox Live Preview',
      title: 'What can be tested',
      details: 'Use a short Kotlin quick test to load a datapack, run a function, and assert sandbox world state.',
    }
  }

  return {
    tag: 'Datapack Sandbox Live Preview',
    title: '能测试什么',
    details: '用一段最短的 Kotlin 快速测试，加载数据包、运行函数并断言沙盒世界状态。',
  }
})
</script>

<template>
  <section class="home-code-showcase">
    <div class="home-code-showcase__intro">
      <p class="home-code-showcase__tag">{{ text.tag }}</p>
      <h2>{{ text.title }}</h2>
      <p>{{ text.details }}</p>
    </div>

    <article class="home-code-showcase__card">
      <div class="home-code-showcase__meta">
        <span>QuickTest</span>
        <span>Scoreboard Assertion</span>
        <span>kotlin</span>
      </div>
      <pre><code>SandboxQuickTest.singleFunctionText(
    "scoreboard objectives add runs dummy\n" +
        "scoreboard players set #unit runs 1",
    version = "26.2",
)
    .function()
    .assertScore("#unit", "runs", 1)
    .requirePassed()</code></pre>
    </article>
  </section>
</template>
