<script setup lang="ts">
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'
import '@datapack-sandbox/vitepress-playground/style.css'
import { useData } from 'vitepress'
import { ref } from 'vue'

const { lang } = useData()
const source = ref(lang.value.startsWith('zh') ? 'say 轻量嵌入示例' : 'say lightweight embedded example')
</script>

<template>
  <DpsCell v-model="source" version="26.2" site-id="documentation-cell-example" />
</template>
