<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Bot,
  ChevronDown,
  ChevronUp,
  Cpu,
  FileText,
  FolderOpen,
  Paperclip,
  RefreshCw,
  Save,
  Search,
  Send,
  Sparkles,
  X,
} from '@lucide/vue'
import { sendChat, type ChatMessage, type SearchResult } from '../services/assistantApi'

type UiMessage = ChatMessage & {
  id: number
  sources?: SearchResult[]
  realtimeSearchUsed?: boolean
  modelAvailable?: boolean
  model?: string
}

const modelOptions = [
  { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
  { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
] as const

const messages = ref<UiMessage[]>([
  {
    id: 1,
    role: 'assistant',
    content: '我已经在线。可以直接提问，也可以选择本地文本文件进行读取、编辑或转换。',
    modelAvailable: true,
  },
])
const draft = ref('')
const realtimeSearch = ref(false)
const selectedModel = ref('deepseek-v4-pro')
const isSending = ref(false)
const filePanelOpen = ref(false)
const selectedFilePath = ref('')
const fileContent = ref('')
const fileDirty = ref(false)
const targetFormat = ref('md')
const errorText = ref('')

const isElectron = computed(() => Boolean(window.assistant))
const assistantStatus = computed(() => (isSending.value ? '思考中' : '在线'))
const showFilePanel = computed(() => filePanelOpen.value)
const selectedFileName = computed(() => {
  if (!selectedFilePath.value) {
    return ''
  }

  return selectedFilePath.value.split(/[\\/]/).pop() || selectedFilePath.value
})
let petIdleTimer: number | undefined

function nextId() {
  return Date.now() + Math.floor(Math.random() * 1000)
}

function appendMessage(message: Omit<UiMessage, 'id'>) {
  messages.value.push({ ...message, id: nextId() })
}

function recentHistory() {
  return messages.value.slice(-8).map(({ role, content }) => ({ role, content }))
}

function modelLabel(model?: string) {
  return modelOptions.find((option) => option.value === model)?.label || model || ''
}

function isSupportedModel(model: string) {
  return modelOptions.some((option) => option.value === model)
}

function setPetState(state: PetState) {
  void window.assistant?.setPetState(state)
}

function schedulePetIdle(delay = 1400) {
  window.clearTimeout(petIdleTimer)
  petIdleTimer = window.setTimeout(() => setPetState('idle'), delay)
}

function markPetSpeaking() {
  setPetState('speaking')
  schedulePetIdle()
}

function markPetError() {
  setPetState('error')
  schedulePetIdle(2200)
}

function prepareDraft(text: string) {
  const modelCommand = text.match(/^\/model\s+([a-zA-Z0-9_.-]+)(?:\s+([\s\S]*))?$/i)
  if (modelCommand) {
    const model = modelCommand[1]
    if (!isSupportedModel(model)) {
      appendMessage({
        role: 'assistant',
        content: `暂不支持模型 ${model}。当前可选模型：${modelOptions.map((option) => option.value).join('、')}。`,
        modelAvailable: false,
      })
      return null
    }

    selectedModel.value = model
    const commandMessage = (modelCommand[2] || '').trim()
    if (!commandMessage) {
      appendMessage({ role: 'assistant', content: `已切换模型为 ${modelLabel(model)}。`, model })
      return null
    }

    return { message: commandMessage, model }
  }

  const modelMention = text.match(/^@([a-zA-Z0-9_.-]+)\s+([\s\S]+)$/i)
  if (modelMention) {
    const model = modelMention[1]
    if (!isSupportedModel(model)) {
      appendMessage({
        role: 'assistant',
        content: `暂不支持模型 ${model}。当前可选模型：${modelOptions.map((option) => option.value).join('、')}。`,
        modelAvailable: false,
      })
      return null
    }

    selectedModel.value = model
    return { message: modelMention[2].trim(), model }
  }

  return { message: text, model: selectedModel.value }
}

async function hideChat() {
  setPetState('idle')
  await window.assistant?.hideChat()
}

async function send() {
  const text = draft.value.trim()
  if (!text || isSending.value) {
    return
  }

  const prepared = prepareDraft(text)
  draft.value = ''
  if (!prepared || !prepared.message) {
    return
  }

  appendMessage({ role: 'user', content: prepared.message })
  isSending.value = true
  errorText.value = ''
  setPetState('thinking')

  try {
    const response = await sendChat({
      message: prepared.message,
      realtimeSearch: realtimeSearch.value,
      model: prepared.model,
      history: recentHistory(),
    })

    appendMessage({
      role: 'assistant',
      content: response.answer,
      sources: response.sources,
      realtimeSearchUsed: response.realtimeSearchUsed,
      modelAvailable: response.modelAvailable,
      model: response.model || prepared.model,
    })
    markPetSpeaking()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    appendMessage({
      role: 'assistant',
      content: `后端暂时不可用：${message}。请确认 Spring Boot 服务已在 8080 端口启动。`,
      modelAvailable: false,
      model: prepared.model,
    })
    markPetError()
  } finally {
    isSending.value = false
  }
}

async function selectFile() {
  if (!window.assistant) {
    return
  }

  filePanelOpen.value = true
  errorText.value = ''
  const result = await window.assistant.files.select()
  if (result.canceled || result.paths.length === 0) {
    return
  }

  selectedFilePath.value = result.paths[0]
  fileContent.value = ''
  fileDirty.value = false
}

async function readSelectedFile() {
  if (!window.assistant || !selectedFilePath.value) {
    return
  }

  try {
    filePanelOpen.value = true
    errorText.value = ''
    const result = await window.assistant.files.readText(selectedFilePath.value)
    fileContent.value = result.content
    fileDirty.value = false
    appendMessage({
      role: 'assistant',
      content: `已读取 ${result.name}，大小 ${Math.round(result.size / 1024)} KB。`,
    })
  } catch (error) {
    filePanelOpen.value = true
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}

async function saveSelectedFile() {
  if (!window.assistant || !selectedFilePath.value) {
    return
  }

  const confirmed = window.confirm(`确认覆盖写入 ${selectedFileName.value}？`)
  if (!confirmed) {
    return
  }

  try {
    filePanelOpen.value = true
    errorText.value = ''
    await window.assistant.files.writeText({
      filePath: selectedFilePath.value,
      content: fileContent.value,
    })
    fileDirty.value = false
    appendMessage({ role: 'assistant', content: `已保存 ${selectedFileName.value}。` })
  } catch (error) {
    filePanelOpen.value = true
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}

async function saveTextAs() {
  if (!window.assistant || !fileContent.value) {
    return
  }

  try {
    filePanelOpen.value = true
    errorText.value = ''
    const result = await window.assistant.files.saveTextAs({
      defaultPath: selectedFilePath.value || undefined,
      content: fileContent.value,
    })
    if (!result.canceled && result.path) {
      selectedFilePath.value = result.path
      fileDirty.value = false
      appendMessage({ role: 'assistant', content: `已另存为 ${result.name || result.path}。` })
    }
  } catch (error) {
    filePanelOpen.value = true
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}

async function convertSelectedFile() {
  if (!window.assistant || !selectedFilePath.value) {
    return
  }

  try {
    filePanelOpen.value = true
    errorText.value = ''
    const result = await window.assistant.files.convertText({
      filePath: selectedFilePath.value,
      targetFormat: targetFormat.value,
    })
    fileContent.value = result.content
    fileDirty.value = true
    appendMessage({
      role: 'assistant',
      content: `已转换为 ${result.targetFormat.toUpperCase()} 内容。确认无误后可另存，建议路径：${result.defaultPath}`,
    })
  } catch (error) {
    filePanelOpen.value = true
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}
</script>

<template>
  <main class="chat-window">
    <header class="chat-header">
      <div class="brand">
        <span class="brand-avatar" aria-hidden="true">
          <Bot :size="18" />
        </span>
        <div class="brand-copy">
          <strong>Universal Assistant</strong>
          <span>{{ assistantStatus }} · {{ modelLabel(selectedModel) }}</span>
        </div>
      </div>
      <div class="header-actions">
        <span class="status-pill" :class="{ active: isSending }">
          <Sparkles :size="13" />
          {{ isSending ? '生成中' : '就绪' }}
        </span>
        <button class="icon-button" type="button" aria-label="Close chat" title="关闭" @click="hideChat">
          <X :size="18" />
        </button>
      </div>
    </header>

    <section class="message-list" aria-live="polite">
      <article v-for="message in messages" :key="message.id" class="message-row" :class="message.role">
        <span v-if="message.role === 'assistant'" class="message-avatar" aria-hidden="true">
          <Bot :size="15" />
        </span>
        <div class="message">
          <p>{{ message.content }}</p>
          <div v-if="message.sources?.length" class="sources">
            <a v-for="source in message.sources" :key="source.url" :href="source.url" target="_blank">
              {{ source.title }}
            </a>
          </div>
          <div
            v-if="message.realtimeSearchUsed || (message.model && message.role === 'assistant') || message.modelAvailable === false"
            class="message-meta"
          >
            <small v-if="message.realtimeSearchUsed">实时检索</small>
            <small v-if="message.model && message.role === 'assistant'">{{ modelLabel(message.model) }}</small>
            <small v-if="message.modelAvailable === false">模型未连接或调用失败</small>
          </div>
        </div>
      </article>
      <article v-if="isSending" class="message-row assistant">
        <span class="message-avatar" aria-hidden="true">
          <Bot :size="15" />
        </span>
        <div class="message typing">
          <span />
          <span />
          <span />
        </div>
      </article>
    </section>

    <section v-if="showFilePanel" class="file-panel" :class="{ disabled: !isElectron }">
      <div class="file-panel-header">
        <div>
          <FileText :size="16" />
          <strong>本地文件</strong>
          <span v-if="selectedFileName">{{ selectedFileName }}</span>
        </div>
        <button class="ghost-icon" type="button" title="收起文件面板" @click="filePanelOpen = false">
          <ChevronDown :size="16" />
        </button>
      </div>
      <div class="tool-row">
        <button class="tool-button" type="button" :disabled="!isElectron" @click="selectFile">
          <FolderOpen :size="15" />
          选择
        </button>
        <button class="tool-button" type="button" :disabled="!selectedFilePath" @click="readSelectedFile">
          <FileText :size="15" />
          读取
        </button>
        <label class="format-picker">
          <span>转为</span>
          <select v-model="targetFormat" :disabled="!selectedFilePath" aria-label="Target format">
            <option value="md">Markdown</option>
            <option value="txt">TXT</option>
            <option value="json">JSON</option>
            <option value="csv">CSV</option>
          </select>
        </label>
        <button class="tool-button" type="button" :disabled="!selectedFilePath" @click="convertSelectedFile">
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
        v-model="fileContent"
        class="file-editor"
        placeholder="读取文件后可在这里编辑内容"
        @input="fileDirty = true"
      />
      <div v-if="selectedFilePath || fileContent" class="tool-row end">
        <button class="tool-button" type="button" :disabled="!selectedFilePath || !fileDirty" @click="saveSelectedFile">
          <Save :size="15" />
          保存
        </button>
        <button class="tool-button" type="button" :disabled="!fileContent" @click="saveTextAs">另存</button>
      </div>
      <p v-if="errorText" class="error-text">{{ errorText }}</p>
    </section>

    <footer class="composer">
      <div class="composer-options">
        <button class="tool-pill" type="button" :class="{ active: showFilePanel }" @click="filePanelOpen = !filePanelOpen">
          <Paperclip :size="15" />
          文件
          <ChevronUp v-if="!showFilePanel" :size="14" />
          <ChevronDown v-else :size="14" />
        </button>
        <label class="model-picker">
          <Cpu :size="14" />
          <select v-model="selectedModel" :disabled="isSending" aria-label="Model">
            <option v-for="model in modelOptions" :key="model.value" :value="model.value">
              {{ model.label }}
            </option>
          </select>
        </label>
        <label class="search-toggle" :class="{ active: realtimeSearch }">
          <input v-model="realtimeSearch" type="checkbox" />
          <Search :size="14" />
          <span>实时检索</span>
        </label>
      </div>
      <div class="input-bar">
        <textarea
          v-model="draft"
          rows="2"
          placeholder="输入问题，Shift + Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <button class="send-button" type="button" :disabled="isSending || !draft.trim()" title="发送" @click="send">
          <Send :size="18" />
        </button>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.chat-window {
  display: grid;
  grid-template-rows: auto 1fr auto auto;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  color: #172033;
  background: #f5f7fb;
  user-select: none;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 12px 14px 12px 16px;
  border-bottom: 1px solid rgba(103, 119, 150, 0.16);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-app-region: drag;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.brand-avatar,
.message-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: #ffffff;
  background: #2563eb;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.22);
}

.brand-avatar {
  width: 34px;
  height: 34px;
  border-radius: 12px;
}

.brand-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.brand-copy strong {
  overflow: hidden;
  color: #141b2d;
  font-size: 15px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy span {
  overflow: hidden;
  color: #6a7488;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.icon-button,
.chat-header button {
  -webkit-app-region: no-drag;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.status-pill,
.tool-pill,
.search-toggle,
.model-picker,
.tool-button,
.ghost-icon,
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

.status-pill {
  height: 28px;
  padding: 0 9px;
  border-color: rgba(20, 184, 166, 0.2);
  border-radius: 999px;
  color: #0f766e;
  background: rgba(20, 184, 166, 0.1);
  font-size: 12px;
}

.status-pill.active {
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
  border-color: rgba(245, 158, 11, 0.24);
}

.icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 10px;
  color: #59657a;
  background: #ffffff;
}

.icon-button:hover {
  color: #ef4444;
  background: #fff1f2;
  border-color: rgba(239, 68, 68, 0.24);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding: 18px 16px;
  overflow-y: auto;
  background: #f5f7fb;
  user-select: text;
}

.message-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  justify-content: flex-start;
}

.message-avatar {
  width: 28px;
  height: 28px;
  border-radius: 10px;
  background: #14b8a6;
  box-shadow: 0 8px 16px rgba(20, 184, 166, 0.22);
}

.message {
  max-width: 86%;
  padding: 11px 13px;
  border: 1px solid rgba(103, 119, 150, 0.14);
  border-radius: 18px;
  box-shadow: 0 10px 26px rgba(30, 41, 59, 0.06);
  user-select: text;
}

.message p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.58;
}

.message-row.user .message {
  color: #ffffff;
  border-color: #2563eb;
  background: #2563eb;
  border-bottom-right-radius: 6px;
}

.message-row.assistant .message {
  background: #ffffff;
  border-bottom-left-radius: 6px;
}

.sources {
  display: grid;
  gap: 4px;
  margin-top: 9px;
}

.sources a {
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
  user-select: text;
}

.message-row.user .sources a {
  color: #dbeafe;
}

.sources a:hover {
  text-decoration: underline;
}

.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.message-meta small {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  color: #59657a;
  background: #eef2f8;
  font-size: 11px;
}

.message-row.user .message-meta small {
  color: #eff6ff;
  background: rgba(255, 255, 255, 0.18);
}

.typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 56px;
  min-height: 38px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: #14b8a6;
  animation: typing-dot 900ms ease-in-out infinite;
}

.typing span:nth-child(2) {
  animation-delay: 120ms;
}

.typing span:nth-child(3) {
  animation-delay: 240ms;
}

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
.ghost-icon,
.tool-pill {
  height: 32px;
  padding: 0 10px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled),
.tool-pill:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
  color: #1d4ed8;
}

.ghost-icon {
  width: 30px;
  padding: 0;
}

.tool-pill {
  border-radius: 999px;
}

.tool-pill.active {
  color: #1d4ed8;
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
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
  grid-template-columns: 1fr 42px;
  gap: 8px;
  align-items: stretch;
}

.input-bar textarea {
  min-width: 0;
  resize: none;
  max-height: 120px;
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

.input-bar textarea:focus,
.file-editor:focus {
  border-color: rgba(37, 99, 235, 0.52);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

button {
  cursor: pointer;
}

button:disabled,
select:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.send-button {
  width: 42px;
  min-width: 42px;
  height: auto;
  border: 0;
  border-radius: 14px;
  color: #ffffff;
  background: #2563eb;
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.24);
}

.send-button:hover:not(:disabled) {
  background: #1d4ed8;
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

@keyframes typing-dot {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }
  50% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@media (max-width: 420px) {
  .status-pill {
    display: none;
  }

  .model-picker {
    flex: 1 1 100%;
    justify-content: flex-start;
  }

  .model-picker select {
    width: 100%;
  }
}
</style>
