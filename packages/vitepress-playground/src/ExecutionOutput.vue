<script setup lang="ts">
import { computed, ref } from 'vue'
import { resolvePlaygroundLabels } from './localization'
import type { PlaygroundLabels, PlaygroundLocale, PlaygroundOutputEvent } from './types'

const props = withDefaults(defineProps<{
  summary: string
  raw?: unknown
  showReadable?: boolean
  showStructured?: boolean
  locale?: PlaygroundLocale
  labels?: Partial<PlaygroundLabels>
}>(), {
  showReadable: true,
  showStructured: true,
  locale: 'en',
  labels: () => ({}),
})

const outputs = computed(() => executionOutputs(props.raw))
const readableOpen = ref(false)
const visibleOutputCount = ref(100)
const visibleOutputs = computed(() => outputs.value.slice(0, visibleOutputCount.value))
const ui = computed(() => resolvePlaygroundLabels(props.locale, props.labels))
const showMoreOutputs = computed(() => ui.value.showMoreOutputs.replace(
  '{count}',
  String(Math.max(0, outputs.value.length - visibleOutputCount.value)),
))

function toggleReadable(event: Event): void {
  readableOpen.value = (event.currentTarget as HTMLDetailsElement).open
  if (!readableOpen.value) visibleOutputCount.value = 100
}

function executionOutputs(raw: unknown): PlaygroundOutputEvent[] {
  if (!raw || typeof raw !== 'object' || !Array.isArray((raw as { outputs?: unknown }).outputs)) return []
  return (raw as { outputs: unknown[] }).outputs.filter((item): item is PlaygroundOutputEvent => (
    Boolean(item)
    && typeof item === 'object'
    && typeof (item as PlaygroundOutputEvent).command === 'string'
    && typeof (item as PlaygroundOutputEvent).text === 'string'
  ))
}

function outputText(output: PlaygroundOutputEvent): string {
  if (output.text) return output.text
  if (output.rawText) return output.rawText
  if (output.payload !== undefined) return JSON.stringify(output.payload)
  return '(no text)'
}
</script>

<template>
  <div class="dps-output">
    <p>{{ summary }}</p>
    <details v-if="showReadable && outputs.length" class="dps-command-outputs" @toggle="toggleReadable">
      <summary>{{ ui.commandOutputs }} ({{ outputs.length }})</summary>
      <ol v-if="readableOpen">
        <li v-for="(output, index) in visibleOutputs" :key="`${output.tick}:${index}:${output.command}`" class="dps-command-output">
          <div class="dps-command-output-heading">
            <code>{{ output.command }}</code>
            <span>{{ output.channel }} · tick {{ output.tick }}</span>
          </div>
          <p>{{ outputText(output) }}</p>
          <small v-if="output.targets.length">Targets: {{ output.targets.join(', ') }}</small>
        </li>
      </ol>
      <button
        v-if="readableOpen && visibleOutputCount < outputs.length"
        class="dps-command-output-more"
        type="button"
        @click="visibleOutputCount += 100"
      >
        {{ showMoreOutputs }}
      </button>
    </details>
    <details v-if="showStructured && raw" class="dps-structured-output">
      <summary>{{ ui.structuredResult }}</summary>
      <pre>{{ JSON.stringify(raw, null, 2) }}</pre>
    </details>
  </div>
</template>
