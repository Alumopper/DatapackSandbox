<script setup lang="ts">
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
import { useData } from 'vitepress'
import { computed } from 'vue'

const { lang } = useData()
const englishNotebook = {
  version: '26.2',
  cells: [
    {
      type: 'markdown' as const,
      source: '# Try it\nThe next cell changes a persistent sandbox world.',
    },
    {
      type: 'code' as const,
      source: 'setblock 0 0 2 minecraft:stone',
    },
  ],
}
const chineseNotebook = {
  version: '26.2',
  cells: [
    {
      type: 'markdown' as const,
      source: '# 在线试用\n下一格命令会修改一个持续存在的沙盒世界。',
    },
    {
      type: 'code' as const,
      source: 'setblock 0 0 2 minecraft:stone',
    },
  ],
}
const notebook = computed(() => lang.value.startsWith('zh') ? chineseNotebook : englishNotebook)

const apiUrl = import.meta.env.VITE_DPS_PLAYGROUND_API_URL || 'http://127.0.0.1:8080'
</script>

<template>
  <DpsPlayground
    :notebook="notebook"
    :api-url="apiUrl"
    :render="{ auto: true, width: 960, height: 540 }"
    site-id="documentation-example"
  />
</template>
