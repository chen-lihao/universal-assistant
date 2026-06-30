<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Bot,
  ChevronDown,
  ChevronUp,
  Cpu,
  Check,
  Edit3,
  FileText,
  FolderOpen,
  History as HistoryIcon,
  Paperclip,
  Plus,
  RefreshCw,
  Save,
  Search,
  Send,
  Sparkles,
  Square,
  Trash2,
  X,
} from '@lucide/vue'
import {
  cancelAgentRun,
  deleteConversation,
  listConversations,
  loadConversationMessages,
  sendChat,
  streamChat,
  updateConversation,
  type AgentStep,
  type ChatMessage,
  type ChatRequest,
  type ChatResponse,
  type ChatStreamEvent,
  type ChatStreamPhase,
  type ConversationMessage,
  type ConversationSummary,
  type MessageBlock,
  type SearchResult,
} from '../services/assistantApi'
import MessageRenderer from './MessageRenderer.vue'

type UiMessage = ChatMessage & {
  id: number | string
  messageId?: string
  sources?: SearchResult[]
  contentBlocks?: MessageBlock[]
  realtimeSearchUsed?: boolean
  modelAvailable?: boolean
  model?: string
  agentRunId?: string
  agentSteps?: AgentStep[]
  status?: string
  revision?: number
  editedAt?: string
  localEditable?: boolean
  pendingTool?: {
    name: string
    input: string
    reason: string
  }
  activeMarkdownBlockId?: string
  phase?: ChatStreamPhase
  isStreaming?: boolean
}

type UiPhase = ChatStreamPhase | 'idle'

const modelOptions = [
  { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
  { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
] as const

const phaseLabels: Record<UiPhase, string> = {
  idle: '在线',
  thinking: '思考中',
  planning: '规划中',
  acting: '调用工具中',
  searching: '检索中',
  waiting_confirmation: '等待确认',
  reflecting: '反思中',
  answering: '回答中',
  cancelled: '回答已中断',
  done: '回答完毕',
  error: '出错',
}

function welcomeMessage(): UiMessage {
  return {
    id: 'welcome',
    role: 'assistant',
    content: '我已经在线。可以直接提问，也可以选择本地文本文件进行读取、编辑或转换。',
    modelAvailable: true,
  }
}

const messages = ref<UiMessage[]>([welcomeMessage()])
const conversations = ref<ConversationSummary[]>([])
const currentConversationId = ref<string>()
const conversationPanelOpen = ref(false)
const conversationsLoading = ref(false)
const draft = ref('')
const realtimeSearch = ref(false)
const selectedModel = ref('deepseek-v4-pro')
const isSending = ref(false)
const currentPhase = ref<UiPhase>('idle')
const filePanelOpen = ref(false)
const selectedFilePath = ref('')
const fileContent = ref('')
const fileDirty = ref(false)
const targetFormat = ref('md')
const errorText = ref('')
const messageListRef = ref<HTMLElement | null>(null)
const draftTextareaRef = ref<HTMLTextAreaElement | null>(null)
const shouldAutoFollowMessages = ref(true)
const showScrollToBottom = ref(false)
const editingMessageId = ref<string | number>()
const renamingConversationId = ref<string>()
const renamingTitle = ref('')
let activeAbortController: AbortController | undefined
let activeAssistantMessage: UiMessage | undefined

const isElectron = computed(() => Boolean(window.assistant))
const assistantStatus = computed(() => phaseLabels[currentPhase.value])
const currentConversationTitle = computed(() => {
  if (!currentConversationId.value) {
    return '新会话'
  }

  return conversations.value.find((conversation) => conversation.id === currentConversationId.value)?.title || '当前会话'
})
const headerSubtitle = computed(() => `${assistantStatus.value} · ${modelLabel(selectedModel.value)} · ${currentConversationTitle.value}`)
const showFilePanel = computed(() => filePanelOpen.value)
const selectedFileName = computed(() => {
  if (!selectedFilePath.value) {
    return ''
  }

  return selectedFilePath.value.split(/[\\/]/).pop() || selectedFilePath.value
})
const editingMessage = computed(() => messages.value.find((message) => message.id === editingMessageId.value && message.role === 'user'))
const isEditingDraft = computed(() => Boolean(editingMessage.value))
const draftPlaceholder = computed(() => (isEditingDraft.value ? '编辑问题，Enter 重新发送，Shift + Enter 换行' : '输入问题，Shift + Enter 换行'))
let petIdleTimer: number | undefined
let phaseResetTimer: number | undefined
let typewriterTimer: number | undefined
let typewriterQueue = ''
let typewriterMessage: UiMessage | undefined
let pendingDoneMessage: UiMessage | undefined
let conversationRetryTimer: number | undefined
let conversationRetryDelay = 2000
const MESSAGE_BOTTOM_THRESHOLD = 110
const DRAFT_MAX_HEIGHT = 160

onMounted(async () => {
  await refreshConversations({ retryOnFailure: true })
  if (!currentConversationId.value && conversations.value.length > 0) {
    await openConversation(conversations.value[0])
  }
  resizeDraftTextarea()
})

onUnmounted(() => {
  window.clearTimeout(conversationRetryTimer)
  window.clearTimeout(phaseResetTimer)
  window.clearTimeout(petIdleTimer)
  window.clearInterval(typewriterTimer)
})

watch(draft, () => resizeDraftTextarea())

function nextId() {
  return Date.now() + Math.floor(Math.random() * 1000)
}

function restoredAssistantContent(message: ConversationMessage) {
  if (message.role !== 'assistant' || message.content.trim()) {
    return message.content
  }

  if (message.status === 'cancelled') {
    return '回答已中断。你可以编辑上一条问题后重新发送。'
  }
  if (message.status === 'running' || message.status === 'waiting_confirmation') {
    return ''
  }
  if (message.status === 'streaming') {
    return '上次生成未完成，未收到可恢复的回答内容。'
  }
  if (message.status === 'failed') {
    return '回答失败，未收到可恢复的回答内容。'
  }

  return '回答内容未保存或未生成完整内容。'
}

function restoredAssistantPhase(message: ConversationMessage): ChatStreamPhase | undefined {
  if (message.role !== 'assistant') {
    return undefined
  }
  if (message.status === 'failed') {
    return 'error'
  }
  if (message.status === 'cancelled') {
    return 'cancelled'
  }
  if (message.status === 'waiting_confirmation') {
    return 'waiting_confirmation'
  }
  if (message.status === 'running') {
    return 'thinking'
  }
  if (message.status === 'streaming') {
    return 'thinking'
  }

  return 'done'
}

function restoredModelAvailable(message: ConversationMessage) {
  if (message.role === 'assistant' && message.status === 'failed') {
    return false
  }

  return message.modelAvailable
}

function toUiMessage(message: ConversationMessage): UiMessage {
  return {
    id: message.id,
    messageId: message.id,
    role: message.role,
    content: restoredAssistantContent(message),
    contentBlocks: message.contentBlocks || [],
    model: message.model,
    realtimeSearchUsed: message.realtimeSearchUsed,
    modelAvailable: restoredModelAvailable(message),
    sources: message.sources || [],
    status: message.status,
    revision: message.revision,
    editedAt: message.editedAt,
    localEditable: message.role === 'user',
    agentRunId: message.agentRunId,
    agentSteps: message.agentSteps || [],
    pendingTool: message.pendingToolName
      ? {
          name: message.pendingToolName,
          input: message.pendingToolInput || '',
          reason: message.pendingToolReason || 'Agent 判断需要实时检索。',
        }
      : undefined,
    phase: restoredAssistantPhase(message),
    isStreaming: false,
  }
}

function appendMessage(message: Omit<UiMessage, 'id'>) {
  const nextMessage = { ...message, id: nextId() }
  messages.value.push(nextMessage)
  forceScrollMessagesToBottom()
  return nextMessage
}

function recentHistory() {
  return messages.value.slice(-8).map(({ role, content }) => ({ role, content }))
}

function clearConversationRetry() {
  window.clearTimeout(conversationRetryTimer)
  conversationRetryTimer = undefined
  conversationRetryDelay = 2000
}

function scheduleConversationRetry() {
  if (conversationRetryTimer !== undefined) {
    return
  }

  const delay = conversationRetryDelay
  conversationRetryDelay = Math.min(Math.round(conversationRetryDelay * 1.8), 10000)
  conversationRetryTimer = window.setTimeout(async () => {
    conversationRetryTimer = undefined
    const loaded = await refreshConversations({ retryOnFailure: true })
    if (loaded && !currentConversationId.value && conversations.value.length > 0) {
      await openConversation(conversations.value[0])
    }
  }, delay)
}

async function refreshConversations(options: { retryOnFailure?: boolean } = {}) {
  conversationsLoading.value = true
  try {
    conversations.value = await listConversations()
    clearConversationRetry()
    if (errorText.value.includes('历史会话加载失败')) {
      errorText.value = ''
    }
    return true
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    errorText.value = `历史会话加载失败：${message}。后端恢复后会自动重试。`
    if (options.retryOnFailure) {
      scheduleConversationRetry()
    }
    return false
  } finally {
    conversationsLoading.value = false
  }
}

function startNewConversation() {
  currentConversationId.value = undefined
  messages.value = [welcomeMessage()]
  editingMessageId.value = undefined
  draft.value = ''
  errorText.value = ''
  conversationPanelOpen.value = false
  setCurrentPhase('idle')
  forceScrollMessagesToBottom()
  resizeDraftTextarea()
}

function createNewConversation() {
  startNewConversation()
}

async function openConversation(conversation: ConversationSummary) {
  if (isSending.value) {
    return
  }

  try {
    errorText.value = ''
    const response = await loadConversationMessages(conversation.id)
    currentConversationId.value = response.conversationId
    const loadedMessages = response.messages.map(toUiMessage)
    messages.value = loadedMessages.length ? loadedMessages : [welcomeMessage()]
    editingMessageId.value = undefined
    draft.value = ''
    conversationPanelOpen.value = false
    setCurrentPhase('idle')
    forceScrollMessagesToBottom()
    resizeDraftTextarea()
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}

async function refreshCurrentConversationMessages() {
  if (!currentConversationId.value) {
    return
  }

  const response = await loadConversationMessages(currentConversationId.value)
  const loadedMessages = response.messages.map(toUiMessage)
  messages.value = loadedMessages.length ? loadedMessages : [welcomeMessage()]
  scrollMessagesToBottom()
}

function formatConversationTime(value: string) {
  if (!value) {
    return ''
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function scrollMessagesToBottom() {
  void nextTick(() => {
    const list = messageListRef.value
    if (!list || (!shouldAutoFollowMessages.value && showScrollToBottom.value)) {
      return
    }

    list.scrollTop = list.scrollHeight
    updateMessageScrollState()
  })
}

function forceScrollMessagesToBottom() {
  shouldAutoFollowMessages.value = true
  showScrollToBottom.value = false
  void nextTick(() => {
    const list = messageListRef.value
    if (!list) {
      return
    }

    list.scrollTop = list.scrollHeight
    updateMessageScrollState()
  })
}

function isNearMessageBottom(list: HTMLElement) {
  return list.scrollHeight - list.scrollTop - list.clientHeight <= MESSAGE_BOTTOM_THRESHOLD
}

function updateMessageScrollState() {
  const list = messageListRef.value
  if (!list) {
    shouldAutoFollowMessages.value = true
    showScrollToBottom.value = false
    return
  }

  const nearBottom = isNearMessageBottom(list)
  shouldAutoFollowMessages.value = nearBottom
  showScrollToBottom.value = !nearBottom
}

function handleMessageListScroll() {
  updateMessageScrollState()
}

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

function canEditUserMessage(message: UiMessage) {
  return message.role === 'user' && Boolean(message.messageId || message.localEditable)
}

function markPreviousUserMessageEditable(assistantMessage: UiMessage) {
  const assistantIndex = messages.value.findIndex((message) => message.id === assistantMessage.id)
  if (assistantIndex <= 0) {
    return
  }

  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    const message = messages.value[index]
    if (message.role === 'user') {
      message.localEditable = true
      return
    }
  }
}

function phaseLabel(phase?: UiPhase) {
  return phase ? phaseLabels[phase] : ''
}

function setCurrentPhase(phase: UiPhase) {
  window.clearTimeout(phaseResetTimer)
  currentPhase.value = phase
}

function resetCurrentPhaseLater(delay = 1400) {
  window.clearTimeout(phaseResetTimer)
  phaseResetTimer = window.setTimeout(() => {
    currentPhase.value = 'idle'
  }, delay)
}

function mergeSources(existing: SearchResult[] | undefined, incoming: SearchResult[] | undefined) {
  const merged = new Map<string, SearchResult>()
  for (const source of existing || []) {
    if (source.url) {
      merged.set(source.url, source)
    }
  }
  for (const source of incoming || []) {
    if (source.url) {
      merged.set(source.url, source)
    }
  }
  return Array.from(merged.values())
}

function completeAssistantMessage(message: UiMessage) {
  if (message.phase === 'error' || message.phase === 'cancelled' || message.status === 'cancelled') {
    return
  }

  message.phase = 'done'
  message.isStreaming = false
  setCurrentPhase('done')
  schedulePetIdle(900)
  resetCurrentPhaseLater()
  scrollMessagesToBottom()
}

function markMessageCancelled(message: UiMessage) {
  flushTypewriter(message)
  if (!message.content.trim()) {
    message.content = '回答已中断。你可以编辑上一条问题后重新发送。'
  }
  message.status = 'cancelled'
  message.phase = 'cancelled'
  message.isStreaming = false
  markPreviousUserMessageEditable(message)
  setCurrentPhase('cancelled')
  schedulePetIdle(600)
  resetCurrentPhaseLater()
  scrollMessagesToBottom()
}

function startTypewriter() {
  if (typewriterTimer !== undefined) {
    return
  }

  typewriterTimer = window.setInterval(() => {
    if (!typewriterMessage || !typewriterQueue) {
      window.clearInterval(typewriterTimer)
      typewriterTimer = undefined
      if (pendingDoneMessage) {
        const message = pendingDoneMessage
        pendingDoneMessage = undefined
        completeAssistantMessage(message)
      }
      return
    }

    const chunkSize = typewriterQueue.length > 80 ? 3 : typewriterQueue.length > 24 ? 2 : 1
    appendAssistantText(typewriterMessage, typewriterQueue.slice(0, chunkSize))
    typewriterQueue = typewriterQueue.slice(chunkSize)
    scrollMessagesToBottom()
  }, 28)
}

function enqueueTypewriterText(message: UiMessage, content: string) {
  if (!content) {
    return
  }

  typewriterMessage = message
  typewriterQueue += content
  startTypewriter()
}

function markDoneAfterTypewriter(message: UiMessage) {
  message.isStreaming = false
  if (typewriterMessage === message && typewriterQueue) {
    pendingDoneMessage = message
    return
  }

  completeAssistantMessage(message)
}

function flushTypewriter(message: UiMessage) {
  if (typewriterMessage !== message) {
    return
  }

  appendAssistantText(message, typewriterQueue)
  typewriterQueue = ''
  window.clearInterval(typewriterTimer)
  typewriterTimer = undefined
  typewriterMessage = undefined
  if (pendingDoneMessage === message) {
    pendingDoneMessage = undefined
  }
}

function appendAssistantText(message: UiMessage, text: string) {
  if (!text) {
    return
  }

  message.content += text
  const markdownBlock = currentMarkdownBlock(message)
  if (markdownBlock) {
    markdownBlock.content += text
  }
}

function currentMarkdownBlock(message: UiMessage) {
  const blockId = message.activeMarkdownBlockId
  const block = (message.contentBlocks || []).find((item) => item.type === 'markdown' && (!blockId || item.id === blockId))
  return block?.type === 'markdown' ? block : undefined
}

function upsertContentBlock(message: UiMessage, block: MessageBlock) {
  const existing = message.contentBlocks || []
  const index = existing.findIndex((item) => item.id === block.id)
  if (index >= 0) {
    const next = [...existing]
    next[index] = block
    message.contentBlocks = next
  } else {
    message.contentBlocks = [...existing, block]
  }
}

function syncExecutionBlock(message: UiMessage) {
  if (!message.agentSteps?.length) {
    return
  }

  const block: MessageBlock = {
    id: 'runtime-execution',
    type: 'execution',
    runId: message.agentRunId,
    steps: message.agentSteps,
    collapsed: false,
  }
  const rest = (message.contentBlocks || []).filter((item) => item.type !== 'execution')
  message.contentBlocks = [block, ...rest]
}

function syncSourcesBlock(message: UiMessage) {
  if (!message.sources?.length) {
    return
  }

  const block: MessageBlock = {
    id: 'runtime-sources',
    type: 'sources',
    items: message.sources,
  }
  const rest = (message.contentBlocks || []).filter((item) => item.type !== 'sources')
  message.contentBlocks = [...rest, block]
}

function clearTypewriterFor(message: UiMessage) {
  if (typewriterMessage !== message && pendingDoneMessage !== message) {
    return
  }

  typewriterQueue = ''
  window.clearInterval(typewriterTimer)
  typewriterTimer = undefined
  if (typewriterMessage === message) {
    typewriterMessage = undefined
  }
  if (pendingDoneMessage === message) {
    pendingDoneMessage = undefined
  }
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

function markPetSpeaking(delay = 1400) {
  setPetState('speaking')
  if (delay > 0) {
    schedulePetIdle(delay)
  }
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

function applyStreamPhase(message: UiMessage, phase: ChatStreamPhase) {
  message.phase = phase
  setCurrentPhase(phase)

  if (phase === 'thinking' || phase === 'planning' || phase === 'searching' || phase === 'acting' || phase === 'reflecting' || phase === 'waiting_confirmation') {
    setPetState('thinking')
  } else if (phase === 'answering') {
    markPetSpeaking(0)
  } else if (phase === 'error') {
    markPetError()
  }

  scrollMessagesToBottom()
}

function applyStreamEvent(event: ChatStreamEvent, message: UiMessage, userMessage?: UiMessage) {
  if (message.status === 'cancelled' && event.type !== 'meta' && event.type !== 'agent_step') {
    return
  }

  if (event.type === 'status' && event.phase) {
    applyStreamPhase(message, event.phase)
    return
  }

  if (event.type === 'meta') {
    if (event.conversationId) {
      currentConversationId.value = event.conversationId
    }
    if (event.messageId) {
      message.messageId = event.messageId
      message.id = event.messageId
    }
    if (event.userMessageId && userMessage) {
      userMessage.messageId = event.userMessageId
      userMessage.id = event.userMessageId
      userMessage.localEditable = true
    }
    if (event.agentRunId) {
      message.agentRunId = event.agentRunId
    }
    message.sources = mergeSources(message.sources, event.sources)
    syncSourcesBlock(message)
    message.realtimeSearchUsed = message.realtimeSearchUsed || Boolean(event.realtimeSearchUsed)
    if (event.modelAvailable !== undefined) {
      message.modelAvailable = event.modelAvailable
    }
    message.model = event.model || message.model
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'agent_step' && event.agentStep) {
    message.agentRunId = event.agentRunId || event.agentStep.runId || message.agentRunId
    const existing = message.agentSteps || []
    const index = existing.findIndex((step) => step.id === event.agentStep?.id)
    if (index >= 0) {
      existing[index] = event.agentStep
    } else {
      existing.push(event.agentStep)
    }
    message.agentSteps = [...existing].sort((a, b) => a.index - b.index)
    syncExecutionBlock(message)
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'tool_confirmation_required') {
    message.agentRunId = event.agentRunId || message.agentRunId
    message.pendingTool = {
      name: event.toolName || 'web_search',
      input: event.toolInput || '',
      reason: event.reason || 'Agent 判断需要实时检索。',
    }
    message.phase = 'waiting_confirmation'
    message.isStreaming = false
    setCurrentPhase('waiting_confirmation')
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'answer_reset') {
    clearTypewriterFor(message)
    message.content = ''
    message.contentBlocks = []
    message.activeMarkdownBlockId = undefined
    message.phase = 'answering'
    message.isStreaming = true
    setCurrentPhase('answering')
    markPetSpeaking(0)
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'block_start' && event.block) {
    if (event.block.type === 'markdown') {
      clearTypewriterFor(message)
      message.content = ''
      message.activeMarkdownBlockId = event.block.id
    }
    upsertContentBlock(message, event.block)
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'block_delta') {
    if (event.blockId) {
      message.activeMarkdownBlockId = event.blockId
    }
    if (message.phase !== 'answering') {
      applyStreamPhase(message, 'answering')
    }
    enqueueTypewriterText(message, event.content || '')
    markPetSpeaking(0)
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'block_end') {
    const block = event.blockId ? (message.contentBlocks || []).find((item) => item.id === event.blockId) : undefined
    if (block?.type === 'markdown') {
      block.streaming = false
    }
    if (message.activeMarkdownBlockId === event.blockId) {
      message.activeMarkdownBlockId = undefined
    }
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'delta') {
    if (message.activeMarkdownBlockId) {
      return
    }
    if (message.phase !== 'answering') {
      applyStreamPhase(message, 'answering')
    }
    enqueueTypewriterText(message, event.content || '')
    markPetSpeaking(0)
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'error') {
    flushTypewriter(message)
    message.modelAvailable = false
    message.phase = 'error'
    message.isStreaming = false
    if (event.message) {
      message.content = message.content ? `${message.content}\n\n${event.message}` : event.message
    }
    setCurrentPhase('error')
    markPetError()
    scrollMessagesToBottom()
    return
  }

  if (event.type === 'done') {
    if (message.phase !== 'error' && message.phase !== 'waiting_confirmation') {
      markDoneAfterTypewriter(message)
    }
    scrollMessagesToBottom()
  }
}

function applyChatResponse(response: ChatResponse, message: UiMessage, fallbackModel: string) {
  flushTypewriter(message)
  if (response.conversationId) {
    currentConversationId.value = response.conversationId
  }
  if (response.messageId) {
    message.messageId = response.messageId
    message.id = response.messageId
  }
  message.agentRunId = response.agentRunId || message.agentRunId
  message.agentSteps = response.agentSteps || message.agentSteps || []
  message.status = response.answer ? message.status : 'error'
  message.sources = mergeSources(message.sources, response.sources)
  message.contentBlocks = (response.contentBlocks || []).filter((block) => block.type !== 'markdown')
  message.realtimeSearchUsed = message.realtimeSearchUsed || response.realtimeSearchUsed
  message.modelAvailable = response.modelAvailable
  message.model = response.model || fallbackModel
  message.content = ''

  if (!response.answer) {
    message.content = '没有收到模型返回内容。'
    message.phase = 'error'
    message.isStreaming = false
    setCurrentPhase('error')
    markPetError()
    scrollMessagesToBottom()
    return
  }

  message.phase = 'answering'
  message.isStreaming = true
  setCurrentPhase('answering')
  markPetSpeaking(0)
  enqueueTypewriterText(message, response.answer)
  markDoneAfterTypewriter(message)
}

async function send() {
  if (editingMessage.value) {
    await submitEditedDraft()
    return
  }

  const text = draft.value.trim()
  if (!text || isSending.value) {
    return
  }

  const prepared = prepareDraft(text)
  draft.value = ''
  resizeDraftTextarea()
  if (!prepared || !prepared.message) {
    return
  }

  const history = recentHistory()
  const userMessage = appendMessage({ role: 'user', content: prepared.message, localEditable: true })
  const assistantMessage = appendMessage({
    role: 'assistant',
    content: '',
    model: prepared.model,
    modelAvailable: true,
    phase: 'thinking',
    isStreaming: true,
  })
  await runChatRequest(prepared.message, prepared.model, assistantMessage, undefined, history, userMessage)
}

async function runChatRequest(message: string, model: string, assistantMessage: UiMessage, editMessageId?: string, requestHistory: ChatMessage[] = recentHistory(), userMessage?: UiMessage) {
  isSending.value = true
  activeAssistantMessage = assistantMessage
  activeAbortController = new AbortController()
  errorText.value = ''
  applyStreamPhase(assistantMessage, 'thinking')
  const chatRequest: ChatRequest = {
    message,
    realtimeSearch: realtimeSearch.value,
    model,
    history: requestHistory,
    conversationId: currentConversationId.value,
    editMessageId,
  }
  let receivedStreamContent = false
  let receivedAnyStreamEvent = false
  let receivedDoneEvent = false

  try {
    await streamChat(chatRequest, {
      onEvent: (event) => {
        receivedAnyStreamEvent = true
        if (event.type === 'done') {
          receivedDoneEvent = true
        }
        if (event.type === 'delta' && event.content?.trim()) {
          receivedStreamContent = true
        }
        applyStreamEvent(event, assistantMessage, userMessage)
      },
    }, activeAbortController.signal)

    if (assistantMessage.phase === 'waiting_confirmation') {
      return
    }

    if (!receivedStreamContent && assistantMessage.phase !== 'error') {
      if (assistantMessage.messageId && currentConversationId.value) {
        await refreshCurrentConversationMessages()
        return
      }

      if (!receivedAnyStreamEvent && !editMessageId) {
        const response = await sendChat(chatRequest)
        applyChatResponse(response, assistantMessage, model)
        return
      }

      if (receivedDoneEvent && !assistantMessage.content.trim()) {
        assistantMessage.content = '没有收到模型返回内容。'
        assistantMessage.modelAvailable = false
        assistantMessage.phase = 'error'
        assistantMessage.isStreaming = false
        setCurrentPhase('error')
        markPetError()
        return
      }

      return
    }

    if (assistantMessage.phase !== 'error') {
      markDoneAfterTypewriter(assistantMessage)
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      markMessageCancelled(assistantMessage)
      return
    }
    try {
      const canUseHttpFallback = !editMessageId && !assistantMessage.messageId && !assistantMessage.agentRunId
      if (canUseHttpFallback && !assistantMessage.content.trim()) {
        const response = await sendChat(chatRequest)
        applyChatResponse(response, assistantMessage, model)
        return
      }

      if (assistantMessage.messageId && currentConversationId.value) {
        await refreshCurrentConversationMessages()
        return
      }

      markDoneAfterTypewriter(assistantMessage)
    } catch (fallbackError) {
      flushTypewriter(assistantMessage)
      const message = fallbackError instanceof Error ? fallbackError.message : String(fallbackError)
      assistantMessage.content = `后端暂时不可用：${message}。请确认 Spring Boot 服务已在 8080 端口启动。`
      assistantMessage.modelAvailable = false
      assistantMessage.phase = 'error'
      assistantMessage.isStreaming = false
      setCurrentPhase('error')
      markPetError()
    }
  } finally {
    isSending.value = false
    activeAbortController = undefined
    activeAssistantMessage = undefined
    void refreshConversations()
    scrollMessagesToBottom()
  }
}

async function stopGeneration() {
  const message = activeAssistantMessage
  const controller = activeAbortController
  if (!isSending.value) {
    return
  }

  if (!message) {
    controller?.abort()
    isSending.value = false
    activeAbortController = undefined
    activeAssistantMessage = undefined
    resetCurrentPhaseLater(0)
    return
  }

  markMessageCancelled(message)

  const cancelRequest = {
    answer: message.content || '回答已中断。你可以编辑上一条问题后重新发送。',
    model: message.model,
    realtimeSearchUsed: Boolean(message.realtimeSearchUsed),
    modelAvailable: message.modelAvailable !== false,
    sources: message.sources || [],
  }
  const runId = message.agentRunId

  isSending.value = false
  activeAbortController = undefined
  activeAssistantMessage = undefined
  resetCurrentPhaseLater()
  scrollMessagesToBottom()

  if (runId) {
    void cancelAgentRun(runId, cancelRequest)
      .then((response) => {
        if (response.messageId) {
          message.messageId = response.messageId
          message.id = response.messageId
        }
        if (response.realtimeSearchUsed) {
          message.realtimeSearchUsed = true
        }
        message.agentSteps = response.agentSteps || message.agentSteps || []
        void refreshConversations()
      })
      .catch((error) => {
        errorText.value = error instanceof Error ? error.message : String(error)
      })
  }

  window.setTimeout(() => {
    if (runId === message.agentRunId) {
      controller?.abort()
    }
  }, runId ? 80 : 0)
}

function startEditMessage(message: UiMessage) {
  if (isSending.value || !canEditUserMessage(message)) {
    return
  }

  editingMessageId.value = message.id
  draft.value = message.content
  void nextTick(() => {
    resizeDraftTextarea()
    draftTextareaRef.value?.focus()
    draftTextareaRef.value?.setSelectionRange(draft.value.length, draft.value.length)
  })
}

function cancelEditMessage() {
  editingMessageId.value = undefined
  draft.value = ''
  resizeDraftTextarea()
}

async function submitEditedDraft() {
  const message = editingMessage.value
  const text = draft.value.trim()
  if (!message || !text || isSending.value || !canEditUserMessage(message)) {
    return
  }

  const messageIndex = messages.value.findIndex((item) => item.id === message.id)
  if (messageIndex < 0) {
    return
  }

  const prepared = prepareDraft(text)
  if (!prepared || !prepared.message) {
    return
  }

  message.content = prepared.message
  message.localEditable = true
  message.revision = (message.revision || 1) + 1
  message.editedAt = new Date().toISOString()
  messages.value = messages.value.slice(0, messageIndex + 1)
  editingMessageId.value = undefined
  draft.value = ''
  resizeDraftTextarea()

  const assistantMessage = appendMessage({
    role: 'assistant',
    content: '',
    model: prepared.model,
    modelAvailable: true,
    phase: 'thinking',
    isStreaming: true,
  })
  const history = messages.value.slice(0, messageIndex).slice(-8).map(({ role, content }) => ({ role, content }))
  await runChatRequest(prepared.message, prepared.model, assistantMessage, message.messageId, history, message)
  if (assistantMessage.phase !== 'waiting_confirmation') {
    try {
      await refreshCurrentConversationMessages()
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }
}

async function handleToolDecision(message: UiMessage, decision: 'approved' | 'denied') {
  if (!message.agentRunId || isSending.value) {
    return
  }

  if (decision === 'approved') {
    realtimeSearch.value = true
    message.realtimeSearchUsed = true
  }
  message.pendingTool = undefined
  message.phase = 'thinking'
  message.isStreaming = true
  message.content = ''
  message.contentBlocks = []
  message.activeMarkdownBlockId = undefined
  message.sources = []
  isSending.value = true
  activeAssistantMessage = message
  activeAbortController = new AbortController()
  setCurrentPhase('thinking')

  const chatRequest: ChatRequest = {
    message: '',
    model: message.model || selectedModel.value,
    conversationId: currentConversationId.value,
    agentRunId: message.agentRunId,
    toolDecision: decision,
  }
  let receivedStreamContent = false

  try {
    await streamChat(chatRequest, {
      onEvent: (event) => {
        if (event.type === 'delta' && event.content?.trim()) {
          receivedStreamContent = true
        }
        applyStreamEvent(event, message)
      },
    }, activeAbortController.signal)
    if (!receivedStreamContent && message.messageId && currentConversationId.value) {
      await refreshCurrentConversationMessages()
      return
    }
    if ((message.phase as UiPhase | undefined) !== 'error') {
      markDoneAfterTypewriter(message)
    }
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      flushTypewriter(message)
      message.content = error instanceof Error ? error.message : String(error)
      message.phase = 'error'
      message.isStreaming = false
      markPetError()
    }
  } finally {
    isSending.value = false
    activeAssistantMessage = undefined
    activeAbortController = undefined
    void refreshConversations()
    scrollMessagesToBottom()
  }
}

async function startRenameConversation(conversation: ConversationSummary) {
  if (isSending.value) {
    return
  }

  renamingConversationId.value = conversation.id
  renamingTitle.value = conversation.title

  await nextTick()
  const input = document.querySelector<HTMLInputElement>(`[data-rename-conversation-id="${conversation.id}"]`)
  input?.focus()
  input?.select()
}

function cancelRenameConversation() {
  renamingConversationId.value = undefined
  renamingTitle.value = ''
}

async function submitRenameConversation(conversation: ConversationSummary) {
  if (isSending.value) {
    return
  }

  const nextTitle = renamingTitle.value.trim()
  if (!nextTitle) {
    return
  }

  if (nextTitle === conversation.title) {
    cancelRenameConversation()
    return
  }

  try {
    const updated = await updateConversation(conversation.id, nextTitle)
    conversations.value = conversations.value.map((item) => (item.id === updated.id ? updated : item))
    cancelRenameConversation()
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error)
  }
}

async function removeConversation(conversation: ConversationSummary) {
  if (isSending.value) {
    return
  }

  const confirmed = window.confirm(`确认删除会话「${conversation.title}」？删除后不会出现在历史列表中。`)
  if (!confirmed) {
    return
  }

  try {
    await deleteConversation(conversation.id)
    conversations.value = conversations.value.filter((item) => item.id !== conversation.id)
    if (renamingConversationId.value === conversation.id) {
      cancelRenameConversation()
    }
    if (currentConversationId.value === conversation.id) {
      startNewConversation()
    }
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : String(error)
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
  <main class="chat-window" :class="{ 'has-conversations': conversationPanelOpen }">
    <header class="chat-header">
      <div class="brand">
        <span class="brand-avatar" aria-hidden="true">
          <Bot :size="18" />
        </span>
        <div class="brand-copy">
          <strong>Universal Assistant</strong>
          <span>{{ headerSubtitle }}</span>
        </div>
      </div>
      <div class="header-actions">
        <span class="status-pill" :class="{ active: isSending, done: currentPhase === 'done', error: currentPhase === 'error' }">
          <Sparkles :size="13" />
          {{ isSending ? assistantStatus : currentPhase === 'idle' ? '就绪' : assistantStatus }}
        </span>
        <button class="icon-button" type="button" aria-label="New conversation" title="新会话" :disabled="isSending" @click="createNewConversation">
          <Plus :size="18" />
        </button>
        <button
          class="icon-button"
          type="button"
          aria-label="Conversation history"
          title="历史会话"
          :class="{ active: conversationPanelOpen }"
          @click="conversationPanelOpen = !conversationPanelOpen"
        >
          <HistoryIcon :size="18" />
        </button>
        <button class="icon-button" type="button" aria-label="Close chat" title="关闭" @click="hideChat">
          <X :size="18" />
        </button>
      </div>
    </header>

    <section v-if="conversationPanelOpen" class="conversation-panel">
      <div class="conversation-panel-header">
        <strong>历史会话</strong>
        <button class="tool-button compact" type="button" :disabled="isSending" @click="startNewConversation">
          <Plus :size="14" />
          临时新会话
        </button>
      </div>
      <div class="conversation-list">
        <div
          v-for="conversation in conversations"
          :key="conversation.id"
          class="conversation-item"
          :class="{ active: conversation.id === currentConversationId, renaming: renamingConversationId === conversation.id }"
        >
          <form
            v-if="renamingConversationId === conversation.id"
            class="conversation-rename"
            @submit.prevent="submitRenameConversation(conversation)"
          >
            <input
              v-model="renamingTitle"
              type="text"
              aria-label="会话名称"
              :data-rename-conversation-id="conversation.id"
              @keydown.esc.prevent="cancelRenameConversation"
            />
            <button class="ghost-icon small" type="submit" title="保存" :disabled="isSending || !renamingTitle.trim()">
              <Check :size="13" />
            </button>
            <button class="ghost-icon small" type="button" title="取消" :disabled="isSending" @click="cancelRenameConversation">
              <X :size="13" />
            </button>
          </form>
          <template v-else>
            <button class="conversation-open" type="button" :disabled="isSending" @click="openConversation(conversation)">
              <span>{{ conversation.title }}</span>
              <small>{{ formatConversationTime(conversation.updatedAt) }}</small>
            </button>
            <div class="conversation-actions">
              <button class="ghost-icon small" type="button" title="重命名" :disabled="isSending" @click="startRenameConversation(conversation)">
                <Edit3 :size="13" />
              </button>
              <button class="ghost-icon small danger" type="button" title="删除" :disabled="isSending" @click="removeConversation(conversation)">
                <Trash2 :size="13" />
              </button>
            </div>
          </template>
        </div>
        <p v-if="!conversationsLoading && conversations.length === 0" class="empty-conversation">暂无历史会话</p>
        <p v-if="conversationsLoading" class="empty-conversation">加载中...</p>
      </div>
      <p v-if="errorText" class="error-text">{{ errorText }}</p>
    </section>

    <section ref="messageListRef" class="message-list" aria-live="polite" @scroll="handleMessageListScroll">
      <article v-for="message in messages" :key="message.id" class="message-row" :class="[message.role, { editing: editingMessageId === message.id }]">
        <span v-if="message.role === 'assistant'" class="message-avatar" aria-hidden="true">
          <Bot :size="15" />
        </span>
        <div class="message-stack">
          <div class="message">
            <MessageRenderer
              v-if="message.role === 'assistant' && (message.content || message.contentBlocks?.length || message.agentSteps?.length || message.sources?.length)"
              :content="message.content"
              :blocks="message.contentBlocks"
              :agent-steps="message.agentSteps"
              :sources="message.sources"
            />
            <p v-else-if="message.role === 'user'">{{ message.content }}</p>
            <div v-if="message.role === 'assistant' && !message.content && message.status !== 'cancelled'" class="message-progress">
              <span class="typing-dots" aria-hidden="true">
                <span />
                <span />
                <span />
              </span>
              <small>{{ phaseLabel(message.phase) || '准备中' }}</small>
            </div>
            <div v-if="message.pendingTool" class="tool-confirmation">
              <strong>需要实时检索</strong>
              <p>{{ message.pendingTool.reason }}</p>
              <small v-if="message.pendingTool.input">检索词：{{ message.pendingTool.input }}</small>
              <div>
                <button class="tool-button compact" type="button" :disabled="isSending" @click="handleToolDecision(message, 'approved')">
                  <Search :size="13" />
                  允许检索
                </button>
                <button class="tool-button compact" type="button" :disabled="isSending" @click="handleToolDecision(message, 'denied')">
                  不联网回答
                </button>
              </div>
            </div>
            <div
              v-if="message.phase || message.editedAt || message.status === 'cancelled' || message.realtimeSearchUsed || (message.model && message.role === 'assistant') || message.modelAvailable === false"
              class="message-meta"
            >
              <small v-if="message.phase && message.role === 'assistant'">{{ phaseLabel(message.phase) }}</small>
              <small v-if="message.status === 'cancelled'">可编辑问题后重发</small>
              <small v-if="message.editedAt && message.role === 'user'">已编辑</small>
              <small v-if="message.realtimeSearchUsed">实时检索</small>
              <small v-if="message.model && message.role === 'assistant'">{{ modelLabel(message.model) }}</small>
              <small v-if="message.modelAvailable === false">模型未连接或调用失败</small>
            </div>
          </div>
          <div v-if="message.role === 'user' && editingMessageId !== message.id && canEditUserMessage(message)" class="message-actions">
            <button class="ghost-icon small" type="button" title="编辑并重发" :disabled="isSending" @click="startEditMessage(message)">
              <Edit3 :size="13" />
            </button>
          </div>
        </div>
      </article>
    </section>

    <button v-if="showScrollToBottom" class="scroll-bottom-button" type="button" @click="forceScrollMessagesToBottom">
      <ChevronDown :size="15" />
      回到底部
    </button>

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
      <div v-if="isEditingDraft" class="composer-edit-banner">
        <span>
          <Edit3 :size="13" />
          正在编辑上一条问题，重发后会覆盖其后续回答
        </span>
        <button class="tool-button compact" type="button" :disabled="isSending" @click="cancelEditMessage">取消编辑</button>
      </div>
      <div class="input-bar">
        <textarea
          ref="draftTextareaRef"
          v-model="draft"
          rows="1"
          :placeholder="draftPlaceholder"
          @input="resizeDraftTextarea"
          @keydown.enter.exact.prevent="send"
        />
        <button
          class="send-button"
          type="button"
          :class="{ stop: isSending, editing: isEditingDraft }"
          :disabled="!isSending && !draft.trim()"
          :title="isSending ? '停止' : isEditingDraft ? '重新发送' : '发送'"
          @click="isSending ? stopGeneration() : send()"
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
  </main>
</template>

<style scoped>
.chat-window {
  position: relative;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  color: #172033;
  background: #f5f7fb;
  user-select: none;
}

.chat-window.has-conversations {
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
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

.status-pill.done {
  color: #0f766e;
  background: rgba(20, 184, 166, 0.12);
  border-color: rgba(20, 184, 166, 0.24);
}

.status-pill.error {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.24);
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

.icon-button.active {
  color: #2563eb;
  background: #eff6ff;
  border-color: rgba(37, 99, 235, 0.28);
}

.icon-button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.conversation-panel {
  display: grid;
  gap: 10px;
  max-height: 210px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(103, 119, 150, 0.14);
  background: rgba(248, 250, 252, 0.96);
  overflow: hidden;
}

.conversation-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.conversation-panel-header strong {
  color: #172033;
  font-size: 13px;
}

.tool-button.compact {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
}

.conversation-list {
  display: grid;
  gap: 6px;
  min-height: 0;
  overflow-y: auto;
}

.conversation-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 38px;
  padding: 5px 6px 5px 9px;
  border: 1px solid rgba(103, 119, 150, 0.14);
  border-radius: 10px;
  color: #344057;
  background: #ffffff;
}

.conversation-open {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
}

.conversation-open span {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-open small {
  color: #7a8598;
  font-size: 11px;
}

.conversation-item:hover,
.conversation-item.active {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: rgba(37, 99, 235, 0.24);
}

.conversation-item.renaming {
  grid-template-columns: minmax(0, 1fr);
}

.conversation-item:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.conversation-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.conversation-rename {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: minmax(0, 1fr) 26px 26px;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.conversation-rename input {
  width: 100%;
  min-width: 0;
  height: 28px;
  padding: 0 8px;
  border: 1px solid rgba(37, 99, 235, 0.34);
  border-radius: 8px;
  outline: none;
  color: #172033;
  background: #ffffff;
  font: inherit;
  font-size: 13px;
}

.conversation-rename input:focus {
  border-color: rgba(37, 99, 235, 0.58);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.empty-conversation {
  margin: 6px 2px;
  color: #7a8598;
  font-size: 12px;
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

.scroll-bottom-button {
  position: absolute;
  right: 18px;
  bottom: 128px;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 999px;
  color: #1d4ed8;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 28px rgba(22, 34, 51, 0.14);
  font: inherit;
  font-size: 12px;
  backdrop-filter: blur(12px);
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

.message-stack {
  display: grid;
  gap: 6px;
  max-width: 86%;
}

.message-row.user .message-stack {
  justify-items: end;
}

.message-row.assistant .message-stack {
  justify-items: start;
  width: min(86%, 860px);
  max-width: 86%;
}

.message {
  width: fit-content;
  max-width: 100%;
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

.message-actions {
  display: flex;
  justify-content: flex-end;
  padding-right: 6px;
}

.message-actions .ghost-icon.small {
  color: #5b6b86;
  background: rgba(255, 255, 255, 0.92);
  border-color: rgba(103, 119, 150, 0.18);
  box-shadow: 0 8px 18px rgba(30, 41, 59, 0.08);
}

.tool-confirmation div {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.message-progress {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 24px;
  color: #64748b;
}

.message-progress small {
  font-size: 12px;
}

.agent-steps {
  width: 100%;
  margin: 0 0 3px;
  border: 1px solid rgba(20, 184, 166, 0.18);
  border-radius: 12px;
  background: rgba(240, 253, 250, 0.72);
  overflow: hidden;
  box-shadow: 0 8px 22px rgba(20, 184, 166, 0.06);
}

.agent-steps:last-child {
  margin-bottom: 0;
}

.agent-steps summary {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 9px;
  color: #0f766e;
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.agent-steps summary::-webkit-details-marker {
  display: none;
}

.agent-steps summary span {
  font-size: 12px;
  font-weight: 700;
}

.agent-steps summary small {
  margin-left: auto;
  color: #64748b;
  font-size: 11px;
}

.agent-steps ol {
  display: grid;
  gap: 6px;
  max-height: 220px;
  margin: 0;
  padding: 8px 9px 9px 28px;
  overflow-y: auto;
  border-top: 1px solid rgba(20, 184, 166, 0.14);
}

.agent-steps li {
  color: #344057;
  font-size: 12px;
}

.agent-steps li.failed {
  color: #b91c1c;
}

.agent-steps li div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.agent-steps li strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-steps li small {
  flex: 0 0 auto;
  color: #64748b;
  font-size: 11px;
}

.agent-steps li p {
  display: -webkit-box;
  max-height: 74px;
  margin: 3px 0 0;
  overflow: hidden;
  color: #526075;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.tool-confirmation {
  display: grid;
  gap: 6px;
  margin-top: 9px;
  padding: 10px;
  border: 1px solid rgba(245, 158, 11, 0.24);
  border-radius: 12px;
  color: #5b3a07;
  background: rgba(255, 251, 235, 0.9);
}

.tool-confirmation strong {
  font-size: 13px;
}

.tool-confirmation p {
  color: #5b3a07;
  font-size: 12px;
}

.tool-confirmation small {
  color: #8a6116;
  font-size: 11px;
  word-break: break-word;
}

.message-row.user .message {
  color: #ffffff;
  border-color: #2563eb;
  background: #2563eb;
  border-bottom-right-radius: 6px;
}

.message-row.user.editing .message {
  outline: 3px solid rgba(37, 99, 235, 0.16);
  box-shadow: 0 12px 30px rgba(37, 99, 235, 0.14);
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
  display: grid;
  gap: 2px;
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
  user-select: text;
}

.sources a small {
  color: #64748b;
  font-size: 11px;
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

.typing,
.typing-dots {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.typing {
  min-width: 56px;
  min-height: 38px;
}

.typing span,
.typing-dots span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: #14b8a6;
  animation: typing-dot 900ms ease-in-out infinite;
}

.typing span:nth-child(2),
.typing-dots span:nth-child(2) {
  animation-delay: 120ms;
}

.typing span:nth-child(3),
.typing-dots span:nth-child(3) {
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

.ghost-icon.small {
  width: 26px;
  height: 26px;
  border-radius: 8px;
}

.ghost-icon.danger:hover:not(:disabled) {
  color: #b91c1c;
  border-color: rgba(239, 68, 68, 0.28);
  background: #fff1f2;
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
