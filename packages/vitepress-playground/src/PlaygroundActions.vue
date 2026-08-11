<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { PlaygroundActionConfig } from './types'
import type { PlaygroundActionItem } from './action-bar'

const props = withDefaults(defineProps<{
  items: PlaygroundActionItem[]
  defaults: PlaygroundActionConfig
  config?: PlaygroundActionConfig
  moreLabel?: string
  moreTitle?: string
}>(), {
  moreLabel: 'More',
  moreTitle: 'More actions',
})

const menu = ref<HTMLDetailsElement>()
const primaryItems = computed(() => props.items.filter((item) => placement(item) === 'primary'))
const menuItems = computed(() => props.items.filter((item) => placement(item) === 'menu'))

function placement(item: PlaygroundActionItem) {
  if (item.visible === false) return 'hidden'
  return props.config?.[item.id] ?? props.defaults[item.id] ?? 'hidden'
}

function invoke(item: PlaygroundActionItem): void {
  closeMenu()
  void item.run()
}

function closeMenu(): void {
  if (menu.value) menu.value.open = false
}

function closeOnOutsidePointer(event: PointerEvent): void {
  const target = event.target
  if (target instanceof Node && menu.value?.open && !menu.value.contains(target)) closeMenu()
}

function closeOnEscape(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || !menu.value?.open) return
  event.preventDefault()
  closeMenu()
  menu.value.querySelector('summary')?.focus()
}

onMounted(() => {
  document.addEventListener('pointerdown', closeOnOutsidePointer, true)
  document.addEventListener('keydown', closeOnEscape)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeOnOutsidePointer, true)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <button
    v-for="item in primaryItems"
    :key="item.id"
    type="button"
    :class="{ 'dps-button-primary': item.emphasis }"
    :data-action="item.id"
    :title="item.title"
    :disabled="item.disabled"
    @click="invoke(item)"
  >
    {{ item.label }}
  </button>
  <details v-if="menuItems.length" ref="menu" class="dps-action-menu">
    <summary :title="moreTitle">
      <span>{{ moreLabel }}</span>
      <span class="dps-action-menu-chevron" aria-hidden="true" />
    </summary>
    <div class="dps-action-menu-panel">
      <button
        v-for="item in menuItems"
        :key="item.id"
        type="button"
        :data-action="item.id"
        :title="item.title"
        :disabled="item.disabled"
        @click="invoke(item)"
      >
        {{ item.label }}
      </button>
    </div>
  </details>
</template>
