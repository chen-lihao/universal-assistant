<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { Check, Cpu, Edit3, Search, Send, Square } from '@lucide/vue'

const props = defineProps<{
  draft: string
  realtimeSearch: boolean
  selectedModel: string
  isSending: boolean
  isEditingDraft: boolean
  placeholder: string
  modelOptions: readonly {
    value: string
    label: string
  }[]
}>()

const emit = defineEmits<{
  'update:draft': [value: string]
  'update:realtimeSearch': [value: boolean]
  'update:selectedModel': [value: string]
  cancelEdit: []
  submit: []
  stop: []
}>()

const draftTextareaRef = ref<HTMLTextAreaElement | null>(null)
const DRAFT_MAX_HEIGHT = 160

onMounted(() => resizeDraftTextarea())

watch(
  () => props.draft,
  () => resizeDraftTextarea(),
)

function resizeDraftTextarea() {
  void nextTick(() => {
    const textarea = draftTextareaRef.value
    if (!textarea) {
      return
    }

    textarea.style.height = 'auto'
    const nextHeight = Math.min(textarea.scrollHeight, DRAFT_MAX_HEIGHT)
    textarea.style.height = `${nextHeight}px`
    textarea.style.overflowY = textarea.scrollHeight > DRAFT_MAX_HEIGHT ? 'auto' : 'hidden'
  })
}

function focusDraftToEnd() {
  void nextTick(() => {
    resizeDraftTextarea()
    const textarea = draftTextareaRef.value
    if (!textarea) {
      return
    }

    textarea.focus()
    textarea.setSelectionRange(props.draft.length, props.draft.length)
  })
}

function updateDraft(event: Event) {
  const target = event.target as HTMLTextAreaElement
  emit('update:draft', target.value)
}

function updateSelectedModel(event: Event) {
  const target = event.target as HTMLSelectElement
  emit('update:selectedModel', target.value)
}

function updateRealtimeSearch(event: Event) {
  const target = event.target as HTMLInputElement
  emit('update:realtimeSearch', target.checked)
}

defineExpose({
  focusDraftToEnd,
  resizeDraftTextarea,
})
</script>

<template>
  <footer class="composer">
    <div class="composer-inner">
      <div class="composer-options">
        <label class="model-picker">
          <Cpu :size="14" />
          <select :value="selectedModel" :disabled="isSending" aria-label="选择模型" @change="updateSelectedModel">
            <option v-for="model in modelOptions" :key="model.value" :value="model.value">
              {{ model.label }}
            </option>
          </select>
        </label>
        <label class="search-toggle" :class="{ active: realtimeSearch }">
          <input :checked="realtimeSearch" type="checkbox" @change="updateRealtimeSearch" />
          <Search :size="14" />
          <span>实时检索</span>
        </label>
      </div>
      <div v-if="isEditingDraft" class="composer-edit-banner">
        <span>
          <Edit3 :size="13" />
          正在编辑上一条问题，重发后会覆盖其后续回答
        </span>
        <button class="tool-button compact" type="button" :disabled="isSending" @click="$emit('cancelEdit')">
          取消编辑
        </button>
      </div>
      <div class="input-bar">
        <textarea
          ref="draftTextareaRef"
          :value="draft"
          rows="1"
          :placeholder="placeholder"
          @input="updateDraft"
          @keydown.enter.exact.prevent="$emit('submit')"
        />
        <button
          class="send-button"
          type="button"
          :class="{ stop: isSending, editing: isEditingDraft }"
          :disabled="!isSending && !draft.trim()"
          :title="isSending ? '停止' : isEditingDraft ? '重新发送' : '发送'"
          @click="isSending ? $emit('stop') : $emit('submit')"
        >
          <Square v-if="isSending" :size="16" />
          <template v-else-if="isEditingDraft">
            <Check :size="15" />
            <span>重发</span>
          </template>
          <Send v-else :size="18" />
        </button>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.composer {
  position: relative;
  padding: 10px clamp(12px, 3vw, 24px) 13px;
  border-top: 1px solid var(--ua-border);
  background: var(--ua-panel);
}

.composer-inner {
  display: grid;
  gap: 9px;
  width: min(100%, 820px);
  margin-inline: auto;
}

.composer-options {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.composer-edit-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-primary-strong);
  background: var(--ua-primary-soft);
  font-size: 12px;
}

.composer-edit-banner span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-pill,
.search-toggle,
.model-picker,
.tool-button,
.send-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--ua-border);
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
  font: inherit;
}

.tool-button,
.tool-pill {
  height: 32px;
  padding: 0 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.tool-button.compact {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 7px 7px 3px 7px;
  font-size: 12px;
}

.tool-button:hover:not(:disabled),
.tool-pill:hover:not(:disabled) {
  border-color: var(--ua-border-strong);
  background: var(--ua-primary-soft);
  color: var(--ua-primary-strong);
}

.tool-pill {
  border-radius: 6px;
}

.tool-pill.active {
  color: var(--ua-primary-strong);
  border-color: var(--ua-border-strong);
  background: var(--ua-primary-soft);
}

.model-picker,
.search-toggle {
  height: 30px;
  padding: 0 9px;
  border-radius: 6px;
  font-size: 12px;
}

.model-picker select {
  width: 140px;
  min-width: 0;
  height: 28px;
  padding: 0 2px;
  border: 0;
  color: var(--ua-ink-soft);
  background: transparent;
  font: inherit;
  outline: none;
}

.search-toggle {
  cursor: pointer;
}

.search-toggle input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.search-toggle.active {
  color: var(--ua-primary-strong);
  border-color: var(--ua-primary);
  background: var(--ua-primary-soft);
}

.input-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: stretch;
  padding: 5px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 8px;
  background: var(--ua-panel-soft);
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.input-bar:focus-within {
  border-color: var(--ua-primary);
  box-shadow: var(--ua-focus);
}

.input-bar textarea {
  min-width: 0;
  min-height: 40px;
  resize: none;
  max-height: 160px;
  overflow-y: hidden;
  padding: 9px 10px;
  border: 0;
  border-radius: 4px;
  color: var(--ua-ink);
  background: transparent;
  font: inherit;
  line-height: 1.45;
  user-select: text;
  outline: none;
}

.input-bar textarea:focus {
  box-shadow: none;
}

.send-button {
  width: 40px;
  min-width: 40px;
  height: auto;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: var(--ua-on-primary);
  background: var(--ua-primary);
  box-shadow: none;
  transition:
    background 160ms ease,
    transform 160ms ease,
    box-shadow 160ms ease;
  white-space: nowrap;
}

.send-button:hover:not(:disabled) {
  background: var(--ua-primary-strong);
  transform: translateY(-1px);
}

.send-button.editing {
  width: 76px;
  min-width: 76px;
  padding: 0 12px;
}

.send-button.stop {
  background: var(--ua-danger);
  box-shadow: none;
}

.send-button.stop:hover:not(:disabled) {
  background: #9d303d;
}

button {
  cursor: pointer;
}

button:disabled,
select:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

@media (max-width: 420px) {
  .model-picker {
    flex: 1 1 100%;
    justify-content: flex-start;
  }

  .model-picker select {
    width: 100%;
  }
}
</style>
