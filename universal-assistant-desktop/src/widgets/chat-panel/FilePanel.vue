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
        <strong>本地文件</strong>
        <span v-if="selectedFileName">{{ selectedFileName }}</span>
      </div>
      <button class="ghost-icon" type="button" title="收起文件面板" @click="$emit('close')">
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
        <span>转为</span>
        <select
          :value="targetFormat"
          :disabled="!selectedFilePath"
          aria-label="Target format"
          @change="updateTargetFormat"
        >
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
      v-if="selectedFilePath || fileContent"
      :value="fileContent"
      class="file-editor"
      placeholder="读取文件后可在这里编辑内容"
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
  display: grid;
  gap: 10px;
  max-height: 240px;
  padding: 12px 14px;
  border-top: 1px solid rgba(103, 119, 150, 0.16);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 -12px 32px rgba(30, 41, 59, 0.05);
}

.file-panel.disabled {
  opacity: 0.55;
}

.file-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.file-panel-header div {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: #172033;
  font-size: 13px;
}

.file-panel-header span {
  overflow: hidden;
  max-width: 230px;
  color: #6a7488;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 32px;
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
  height: 32px;
  padding: 0 10px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 10px;
  color: #344057;
  background: #ffffff;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
  color: #1d4ed8;
}

.ghost-icon {
  width: 30px;
  padding: 0;
}

.file-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  color: #6a7488;
  font-size: 12px;
}

.file-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta strong {
  flex: 0 0 auto;
  color: #f97316;
  font-weight: 600;
}

.file-editor {
  width: 100%;
  height: 88px;
  resize: vertical;
  box-sizing: border-box;
  padding: 10px 11px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 12px;
  color: #172033;
  background: #f8fafc;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  user-select: text;
}

.file-editor:focus {
  border-color: rgba(37, 99, 235, 0.52);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
  outline: none;
}

.format-picker {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 9px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 10px;
  color: #59657a;
  background: #ffffff;
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
  color: #dc2626;
  font-size: 12px;
}

button:disabled,
select:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
</style>
