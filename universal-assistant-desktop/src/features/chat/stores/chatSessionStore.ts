import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { ChatMessage, ChatStreamPhase, ConversationMessage } from '../../../services/assistantApi'
import {
  DEFAULT_MODEL,
  modelLabel,
  phaseLabels,
  welcomeMessage,
  type UiMessage,
  type UiPhase,
} from '../model/chatTypes'

function nextMessageId() {
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
  if (message.status === 'running' || message.status === 'streaming') {
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

export const useChatSessionStore = defineStore('chatSession', () => {
  const messages = ref<UiMessage[]>([welcomeMessage()])
  const currentConversationId = ref<string>()
  const draft = ref('')
  const realtimeSearch = ref(false)
  const selectedModel = ref(DEFAULT_MODEL)
  const isSending = ref(false)
  const currentPhase = ref<UiPhase>('idle')
  const errorText = ref('')

  const assistantStatus = computed(() => phaseLabels[currentPhase.value])
  const selectedModelLabel = computed(() => modelLabel(selectedModel.value))
  const editingMessage = (editingMessageId: string | number | undefined) =>
    messages.value.find((message) => message.id === editingMessageId && message.role === 'user')

  function resetSession() {
    currentConversationId.value = undefined
    messages.value = [welcomeMessage()]
    draft.value = ''
    errorText.value = ''
    currentPhase.value = 'idle'
  }

  function setMessages(nextMessages: UiMessage[]) {
    messages.value = nextMessages.length ? nextMessages : [welcomeMessage()]
  }

  function setConversationMessages(conversationId: string, conversationMessages: ConversationMessage[]) {
    currentConversationId.value = conversationId
    setMessages(conversationMessages.map(toUiMessage))
  }

  function appendMessage(message: Omit<UiMessage, 'id'>) {
    const nextMessage = { ...message, id: nextMessageId() }
    messages.value.push(nextMessage)
    return messages.value[messages.value.length - 1]
  }

  function getMessage(messageId: UiMessage['id']) {
    return messages.value.find((item) => item.id === messageId)
  }

  function patchMessage(messageId: UiMessage['id'], updater: (message: UiMessage) => void) {
    const message = getMessage(messageId)
    if (!message) {
      return undefined
    }

    updater(message)
    return message
  }

  function recentHistory(limit = 8): ChatMessage[] {
    return messages.value.slice(-limit).map(({ role, content }) => ({ role, content }))
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

  return {
    messages,
    currentConversationId,
    draft,
    realtimeSearch,
    selectedModel,
    isSending,
    currentPhase,
    errorText,
    assistantStatus,
    selectedModelLabel,
    editingMessage,
    resetSession,
    setMessages,
    setConversationMessages,
    appendMessage,
    getMessage,
    patchMessage,
    recentHistory,
    canEditUserMessage,
    markPreviousUserMessageEditable,
  }
})
