<script setup lang="ts">
import { ChevronDown, FileText, FolderOpen, RefreshCw, Save } from '@lucide/vue'

defineProps<{
  isElectron: boolean
  selectedFileName: string
  selectedFilePath: string
  fileContent: string
  fileDirty: boolean
  targetFormat: string
  errorText: string
}>()

const emit = defineEmits<{
  close: []
  selectFile: []
  readFile: []
  convertFile: []
  saveFile: []
  saveAs: []
  'update:fileContent': [value: string]
  'update:fileDirty': [value: boolean]
  'update:targetFormat': [value: string]
}>()

function updateFileContent(event: Event) {
  const target = event.target as HTMLTextAreaElement
  emit('update:fileContent', target.value)
  emit('update:fileDirty', true)
}

function updateTargetFormat(event: Event) {
  const target = event.target as HTMLSelectElement
  emit('update:targetFormat', target.value)
}
</script>

<template>
  <section class="file-panel" :class="{ disabled: !isElectron }">
    <div class="file-panel-header">
      <div>
        <FileText :size="16" />
        <span class="file-title">
          <small>本地工具</small>
          <strong>文件工作区</strong>
        </span>
        <span v-if="selectedFileName">{{ selectedFileName }}</span>
      </div>
      <button class="ghost-icon" type="button" title="返回聊天" aria-label="返回聊天" @click="$emit('close')">
        <ChevronDown :size="16" />
      </button>
    </div>
    <div class="tool-row">
      <button class="tool-button" type="button" :disabled="!isElectron" @click="$emit('selectFile')">
        <FolderOpen :size="15" />
        选择
      </button>
      <button class="tool-button" type="button" :disabled="!selectedFilePath" @click="$emit('readFile')">
        <FileText :size="15" />
        读取
      </button>
      <label class="format-picker">
        <span>目标格式</span>
        <select :value="targetFormat" :disabled="!selectedFilePath" aria-label="目标格式" @change="updateTargetFormat">
          <option value="md">Markdown</option>
          <option value="txt">TXT</option>
          <option value="json">JSON</option>
          <option value="csv">CSV</option>
        </select>
      </label>
      <button class="tool-button" type="button" :disabled="!selectedFilePath" @click="$emit('convertFile')">
        <RefreshCw :size="15" />
        转换
      </button>
    </div>
    <div v-if="selectedFilePath" class="file-meta">
      <span>{{ selectedFilePath }}</span>
      <strong v-if="fileDirty">未保存</strong>
    </div>
    <textarea
      :value="fileContent"
      class="file-editor"
      :placeholder="
        isElectron ? '选择本地文件后，在这里读取、编辑和转换内容' : '请在 Electron 桌面端使用本地文件工作区'
      "
      :disabled="!isElectron"
      @input="updateFileContent"
    />
    <div v-if="selectedFilePath || fileContent" class="tool-row end">
      <button class="tool-button" type="button" :disabled="!selectedFilePath || !fileDirty" @click="$emit('saveFile')">
        <Save :size="15" />
        保存
      </button>
      <button class="tool-button" type="button" :disabled="!fileContent" @click="$emit('saveAs')">另存</button>
    </div>
    <p v-if="errorText" class="error-text">{{ errorText }}</p>
  </section>
</template>

<style scoped>
.file-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
  padding: clamp(18px, 3vw, 34px);
  background: var(--ua-bg);
}

.file-panel.disabled {
  opacity: 0.72;
}

.file-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ua-border);
}

.file-panel-header div {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--ua-ink);
  font-size: 14px;
}

.file-title {
  display: grid;
  gap: 0;
  padding-left: 8px;
  border-left: 3px solid var(--ua-companion);
}

.file-title small {
  color: var(--ua-companion-strong);
  font-size: 11px;
  font-weight: 700;
}

.file-title strong {
  color: var(--ua-ink);
}

.file-panel-header > div > span:not(.file-title) {
  overflow: hidden;
  max-width: 230px;
  color: var(--ua-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-height: 36px;
}

.tool-row.end {
  justify-content: flex-end;
}

.tool-button,
.ghost-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled) {
  border-color: var(--ua-border-strong);
  background: var(--ua-primary-soft);
  color: var(--ua-primary-strong);
}

.ghost-icon {
  width: 36px;
  padding: 0;
}

.file-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  color: var(--ua-muted);
  font-size: 12px;
}

.file-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta strong {
  flex: 0 0 auto;
  color: var(--ua-sun);
  font-weight: 600;
}

.file-editor {
  flex: 1 1 auto;
  width: min(100%, 920px);
  min-height: 260px;
  height: auto;
  resize: none;
  box-sizing: border-box;
  padding: 18px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 8px;
  color: var(--ua-ink);
  background: var(--ua-panel);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.65;
  user-select: text;
}

.file-editor:focus {
  border-color: var(--ua-primary);
  box-shadow: var(--ua-focus);
  outline: none;
}

.format-picker {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 9px;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-muted);
  background: var(--ua-panel);
  font-size: 12px;
}

.format-picker select {
  width: 96px;
  height: 28px;
  border: 0;
  background: transparent;
  font: inherit;
  outline: none;
}

.error-text {
  margin: 0;
  color: var(--ua-danger);
  font-size: 12px;
}

button:disabled,
select:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
</style>
