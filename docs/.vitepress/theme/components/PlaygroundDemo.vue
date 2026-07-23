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
      source: '# Try it\nThe next cell changes a persistent sandbox world in this browser tab.',
    },
    {
      type: 'code' as const,
      source: `summon minecraft:block_display -1 0 2 {block_state:{Name:"minecraft:stone"},transformation:{translation:[0f,0.25f,0f],scale:[0.8f,1.4f,0.8f]},brightness:{sky:15,block:15}}
summon minecraft:item_display 1 1 2 {item:{id:"minecraft:diamond",count:1},item_display:"fixed",billboard:"vertical",brightness:{sky:15,block:15}}
summon minecraft:text_display 0 2 2 {text:'{"text":"DISPLAY","color":"yellow"}',billboard:"center",background:-13421773,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}`,
    },
  ],
}
const chineseNotebook = {
  version: '26.2',
  cells: [
    {
      type: 'markdown' as const,
      source: '# 在线试用\n下一格命令会修改当前浏览器标签页里持续存在的沙盒世界。',
    },
    {
      type: 'code' as const,
      source: `summon minecraft:block_display -1 0 2 {block_state:{Name:"minecraft:stone"},transformation:{translation:[0f,0.25f,0f],scale:[0.8f,1.4f,0.8f]},brightness:{sky:15,block:15}}
summon minecraft:item_display 1 1 2 {item:{id:"minecraft:diamond",count:1},item_display:"fixed",billboard:"vertical",brightness:{sky:15,block:15}}
summon minecraft:text_display 0 2 2 {text:'{"text":"DISPLAY ENTITY","color":"yellow"}',billboard:"center",background:-13421773,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}`,
    },
  ],
}
const notebook = computed(() => lang.value.startsWith('zh') ? chineseNotebook : englishNotebook)
</script>

<template>
  <DpsPlayground
    :notebook="notebook"
    :render="{ auto: true, width: 960, height: 540 }"
    :viewport="{ autoplay: false, tickRate: 20 }"
    site-id="documentation-example"
  />
</template>
