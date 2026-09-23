<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { Check, ChevronDown, ChevronUp, Cpu, Edit3, Paperclip, Search, Send, Square } from '@lucide/vue'

const props = defineProps<{
  draft: string
  realtimeSearch: boolean
  selectedModel: string
  isSending: boolean
  showFilePanel: boolean
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
  toggleFilePanel: []
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
    <div class="composer-options">
      <button class="tool-pill" type="button" :class="{ active: showFilePanel }" @click="$emit('toggleFilePanel')">
        <Paperclip :size="15" />
        文件
        <ChevronUp v-if="!showFilePanel" :size="14" />
        <ChevronDown v-else :size="14" />
      </button>
      <label class="model-picker">
        <Cpu :size="14" />
        <select :value="selectedModel" :disabled="isSending" aria-label="Model" @change="updateSelectedModel">
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
  </footer>
</template>

<style scoped>
.composer {
  display: grid;
  gap: 8px;
  padding: 12px 14px 14px;
  border-top: 1px solid rgba(103, 119, 150, 0.16);
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(16px);
}

.composer-options {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.composer-edit-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 12px;
  color: #1e40af;
  background: rgba(239, 246, 255, 0.9);
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
  border: 1px solid rgba(103, 119, 150, 0.18);
  color: #344057;
  background: #ffffff;
  font: inherit;
}

.tool-button,
.tool-pill {
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
}

.tool-button.compact {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
}

.tool-button:hover:not(:disabled),
.tool-pill:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
  color: #1d4ed8;
}

.tool-pill {
  border-radius: 999px;
}

.tool-pill.active {
  color: #1d4ed8;
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
}

.model-picker,
.search-toggle {
  height: 32px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
}

.model-picker select {
  width: 158px;
  min-width: 0;
  height: 28px;
  padding: 0 2px;
  border: 0;
  color: #344057;
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
  color: #0f766e;
  border-color: rgba(20, 184, 166, 0.28);
  background: rgba(20, 184, 166, 0.1);
}

.input-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: stretch;
}

.input-bar textarea {
  min-width: 0;
  min-height: 46px;
  resize: none;
  max-height: 160px;
  overflow-y: hidden;
  padding: 11px 12px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 14px;
  color: #172033;
  background: #f8fafc;
  font: inherit;
  line-height: 1.45;
  user-select: text;
  outline: none;
}

.input-bar textarea:focus {
  border-color: rgba(37, 99, 235, 0.52);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.send-button {
  width: 42px;
  min-width: 42px;
  height: auto;
  padding: 0;
  border: 0;
  border-radius: 14px;
  color: #ffffff;
  background: #2563eb;
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.24);
  white-space: nowrap;
}

.send-button:hover:not(:disabled) {
  background: #1d4ed8;
}

.send-button.editing {
  width: 76px;
  min-width: 76px;
  padding: 0 12px;
}

.send-button.stop {
  background: #ef4444;
  box-shadow: 0 12px 24px rgba(239, 68, 68, 0.22);
}

.send-button.stop:hover:not(:disabled) {
  background: #dc2626;
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
