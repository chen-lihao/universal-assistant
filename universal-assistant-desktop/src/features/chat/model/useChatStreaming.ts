import { storeToRefs } from 'pinia'
import type { ChatMessage } from '../../../services/assistantApi'
import type { UiMessage, UiPhase } from './chatTypes'
import { appendAssistantText } from './messageBlocks'
import { useTypewriter } from './useTypewriter'
import { prepareChatDraft } from './chatDraftPreparation'
import { createChatPetStateController } from './chatPetState'
import { createChatStreamEventApplier } from './chatStreamEvents'
import { createChatStreamRunner } from './chatStreamRunner'
import { markMessageCancelled as applyMessageCancelled } from './chatCancellation'
import { canFinishMessage, isCancelledMessage } from './chatStreamUtils'
import { useChatSessionStore } from '../stores/chatSessionStore'
import { useMessageEditingStore } from '../stores/messageEditingStore'

export function useChatStreaming(options: {
  scrollMessagesToBottom: () => void
  forceScrollMessagesToBottom: () => void
  refreshConversations: () => void | Promise<unknown>
  refreshCurrentConversationMessages: () => Promise<void>
  focusDraftToEnd: () => void
}) {
  const chatStore = useChatSessionStore()
  const messageEditingStore = useMessageEditingStore()
  const { messages, currentConversationId, draft, realtimeSearch, selectedModel, isSending, currentPhase, errorText } =
    storeToRefs(chatStore)
  const { editingMessage } = storeToRefs(messageEditingStore)
  const { canEditUserMessage, getMessage, markPreviousUserMessageEditable, patchMessage, recentHistory } = chatStore

  let phaseResetTimer: number | undefined
  const petState = createChatPetStateController()

  const typewriter = useTypewriter<UiMessage>({
    appendText: appendAssistantText,
    onTick: options.scrollMessagesToBottom,
    onDone: completeAssistantMessage,
  })

  function appendMessage(message: Omit<UiMessage, 'id'>) {
    const nextMessage = chatStore.appendMessage(message)
    options.forceScrollMessagesToBottom()
    return nextMessage
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

  function completeAssistantMessage(message: UiMessage) {
    if (!canFinishMessage(message)) {
      return
    }

    message.phase = 'done'
    message.isStreaming = false
    setCurrentPhase('done')
    petState.schedulePetIdle(900)
    resetCurrentPhaseLater()
    options.scrollMessagesToBottom()
  }

  function enqueueTypewriterText(message: UiMessage, content: string) {
    typewriter.enqueue(message, content)
  }

  function markDoneAfterTypewriter(message: UiMessage) {
    if (!canFinishMessage(message)) {
      return
    }

    message.isStreaming = false
    typewriter.markDone(message)
  }

  function flushTypewriter(message: UiMessage) {
    typewriter.flush(message)
  }

  function clearTypewriterFor(message: UiMessage) {
    typewriter.clearFor(message)
  }

  function markMessageCancelled(message: UiMessage) {
    applyMessageCancelled({
      message,
      flushTypewriter,
      markPreviousUserMessageEditable,
      setCurrentPhase,
      schedulePetIdle: petState.schedulePetIdle,
      resetCurrentPhaseLater,
      scrollMessagesToBottom: options.scrollMessagesToBottom,
    })
  }

  const streamEvents = createChatStreamEventApplier({
    currentConversationId,
    setCurrentPhase,
    clearTypewriterFor,
    enqueueTypewriterText,
    flushTypewriter,
    markDoneAfterTypewriter,
    markPetSpeaking: petState.markPetSpeaking,
    markPetError: petState.markPetError,
    setPetState: petState.setPetState,
    scrollMessagesToBottom: options.scrollMessagesToBottom,
    getMessage,
    patchMessage,
  })

  const streamRunner = createChatStreamRunner({
    currentConversationId,
    realtimeSearch,
    selectedModel,
    isSending,
    errorText,
    messages,
    refreshConversations: options.refreshConversations,
    refreshCurrentConversationMessages: options.refreshCurrentConversationMessages,
    scrollMessagesToBottom: options.scrollMessagesToBottom,
    applyStreamEvent: streamEvents.applyStreamEvent,
    applyStreamPhase: streamEvents.applyStreamPhase,
    flushTypewriter,
    enqueueTypewriterText,
    markDoneAfterTypewriter,
    markMessageCancelled,
    setCurrentPhase,
    markPetSpeaking: petState.markPetSpeaking,
    markPetError: petState.markPetError,
  })

  async function hideChat() {
    await petState.hideChat()
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

    const prepared = prepareChatDraft(text, { selectedModel, appendAssistantMessage: appendMessage })
    draft.value = ''
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
    await streamRunner.runChatRequest(
      prepared.message,
      prepared.model,
      assistantMessage,
      undefined,
      history,
      userMessage,
    )
  }

  function startEditMessage(message: UiMessage) {
    if (!messageEditingStore.startEdit(message)) {
      return
    }

    options.focusDraftToEnd()
  }

  function cancelEditMessage() {
    messageEditingStore.cancelEdit()
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

    const prepared = prepareChatDraft(text, { selectedModel, appendAssistantMessage: appendMessage })
    if (!prepared || !prepared.message) {
      return
    }

    message.content = prepared.message
    message.localEditable = true
    message.revision = (message.revision || 1) + 1
    message.editedAt = new Date().toISOString()
    messages.value = messages.value.slice(0, messageIndex + 1)
    messageEditingStore.clearEditing()
    draft.value = ''

    const assistantMessage = appendMessage({
      role: 'assistant',
      content: '',
      model: prepared.model,
      modelAvailable: true,
      phase: 'thinking',
      isStreaming: true,
    })
    const history: ChatMessage[] = messages.value
      .slice(0, messageIndex)
      .slice(-8)
      .map(({ role, content }) => ({ role, content }))
    await streamRunner.runChatRequest(
      prepared.message,
      prepared.model,
      assistantMessage,
      message.messageId,
      history,
      message,
    )
    if (!isCancelledMessage(assistantMessage) && assistantMessage.phase !== 'waiting_confirmation') {
      try {
        await options.refreshCurrentConversationMessages()
      } catch (error) {
        errorText.value = error instanceof Error ? error.message : String(error)
      }
    }
  }

  function disposeChatStreaming() {
    streamRunner.dispose()
    window.clearTimeout(phaseResetTimer)
    petState.dispose()
  }

  return {
    send,
    stopGeneration: streamRunner.stopGeneration,
    startEditMessage,
    cancelEditMessage,
    handleToolDecision: streamRunner.handleToolDecision,
    hideChat,
    disposeChatStreaming,
  }
}
