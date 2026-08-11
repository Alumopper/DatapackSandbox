<script setup lang="ts">
import { computed, ref } from 'vue'
import CodeCell from './CodeCell.vue'
import { resolvePlaygroundLabels } from './localization'
import type { PlaygroundCompletion, PlaygroundDiagnostic, PlaygroundFunctionSource, PlaygroundLabels, PlaygroundLocale } from './types'

const props = withDefaults(defineProps<{
  modelValue: string
  cellId: string
  readOnly: boolean
  disabled: boolean
  diagnostics: PlaygroundDiagnostic[]
  complete: (source: string, cursor: number) => Promise<PlaygroundCompletion[]>
  check: (source: string) => Promise<PlaygroundDiagnostic[]>
  resolveFunction: (id: string) => Promise<PlaygroundFunctionSource>
  compact?: boolean
  locale?: PlaygroundLocale
  labels?: Partial<PlaygroundLabels>
}>(), {
  compact: false,
  locale: 'en',
  labels: () => ({}),
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  run: []
}>()

const stack = ref<PlaygroundFunctionSource[]>([])
const navigationError = ref('')
const loadingReference = ref('')
const active = computed(() => stack.value.at(-1))
const displayedSource = computed(() => active.value?.source ?? props.modelValue)
const displayedId = computed(() => active.value?.id ?? props.cellId)
const ui = computed(() => resolvePlaygroundLabels(props.locale, props.labels))

async function openFunction(id: string): Promise<void> {
  if (loadingReference.value) return
  navigationError.value = ''
  loadingReference.value = id
  try {
    stack.value.push(await props.resolveFunction(id))
  } catch (error) {
    navigationError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loadingReference.value = ''
  }
}

function goBack(): void {
  if (stack.value.length > 0) stack.value.pop()
  navigationError.value = ''
}

function goToDepth(depth: number): void {
  stack.value.splice(depth)
  navigationError.value = ''
}

function run(): void {
  if (!active.value) emit('run')
}
</script>

<template>
  <div class="dps-function-viewer" :data-definition-depth="stack.length">
    <nav v-if="active" class="dps-function-navigation" aria-label="Function call navigation">
      <button type="button" data-action="function-back" :title="ui.backToCallerTitle" @click="goBack">← {{ ui.back }}</button>
      <div class="dps-function-breadcrumbs">
        <button type="button" @click="goToDepth(0)">{{ cellId }}</button>
        <template v-for="(item, index) in stack" :key="`${index}:${item.id}`">
          <span aria-hidden="true">/</span>
          <button v-if="index < stack.length - 1" type="button" @click="goToDepth(index + 1)">{{ item.id }}</button>
          <strong v-else>{{ item.id }}</strong>
        </template>
      </div>
      <small :title="active.path">{{ active.path }}</small>
    </nav>
    <div v-else-if="loadingReference" class="dps-function-navigation-status" role="status">
      Opening {{ loadingReference }}…
    </div>
    <div v-if="navigationError" class="dps-function-navigation-error" role="alert">{{ navigationError }}</div>
    <CodeCell
      :model-value="displayedSource"
      :cell-id="displayedId"
      :read-only="Boolean(active) || readOnly"
      :disabled="disabled"
      :diagnostics="active ? [] : diagnostics"
      :complete="complete"
      :check="check"
      :can-navigate-back="Boolean(active)"
      @update:model-value="active ? undefined : emit('update:modelValue', $event)"
      @run="run"
      @open-function="openFunction"
      @navigate-back="goBack"
    />
    <div class="dps-editor-hint" :class="{ 'dps-editor-hint-compact': compact }">
      <span><kbd>Ctrl/⌘</kbd> + click a function to open it</span>
      <span v-if="active"><kbd>Alt</kbd> + <kbd>←</kbd> or Mouse Back: caller</span>
      <template v-else-if="!readOnly && !compact">
        <span><kbd>Tab</kbd> accept suggestion</span>
        <span><kbd>Ctrl/⌘</kbd> + <kbd>Enter</kbd> run cell</span>
      </template>
    </div>
  </div>
</template>
