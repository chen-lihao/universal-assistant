import type { Ref } from 'vue'
import { cancelAgentRun, streamChat, type ChatMessage, type ChatStreamEvent } from '../../../services/assistantApi'
import type { UiMessage, UiPhase } from './chatTypes'
import { isCancelledMessage, isWaitingForToolConfirmation } from './chatStreamUtils'

type StreamTarget = {
  assistantMessage: UiMessage
  userMessage?: UiMessage
}

type RunnerOptions = {
  currentConversationId: Ref<string | undefined>
  realtimeSearch: Ref<boolean>
  selectedModel: Ref<string>
  isSending: Ref<boolean>
  errorText: Ref<string>
  messages: Ref<UiMessage[]>
  refreshConversations: () => void | Promise<unknown>
  refreshCurrentConversationMessages: () => Promise<void>
  scrollMessagesToBottom: () => void
  applyStreamEvent: (event: ChatStreamEvent, target: StreamTarget) => void
  applyStreamPhase: (phase: UiPhase, message?: UiMessage) => void
  flushTypewriter: (message: UiMessage) => void
  enqueueTypewriterText: (message: UiMessage, content: string) => void
  markDoneAfterTypewriter: (message: UiMessage) => void
  markMessageCancelled: (message: UiMessage) => void
  setCurrentPhase: (phase: UiPhase) => void
  markPetSpeaking: (delay?: number) => void
  markPetError: () => void
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

function lastActiveAssistant(messages: UiMessage[]) {
  return [...messages]
    .reverse()
    .find(
      (message) =>
        message.role === 'assistant' &&
        (message.isStreaming || message.phase === 'thinking' || message.phase === 'answering'),
    )
}

function responseSnapshot(message: UiMessage) {
  return {
    answer: message.content,
    model: message.model,
    realtimeSearchUsed: Boolean(message.realtimeSearchUsed),
    modelAvailable: message.modelAvailable !== false,
    sources: message.sources || [],
  }
}

export function createChatStreamRunner(options: RunnerOptions) {
  let abortController: AbortController | undefined
  let activeAssistantMessage: UiMessage | undefined

  function clearActiveRequest(message?: UiMessage) {
    if (!message || activeAssistantMessage === message) {
      activeAssistantMessage = undefined
      abortController = undefined
    }
  }

  function failMessage(message: UiMessage, error: unknown) {
    const text = error instanceof Error ? error.message : String(error)
    options.flushTypewriter(message)
    message.phase = 'error'
    message.status = 'failed'
    message.isStreaming = false
    message.modelAvailable = false
    if (!message.content) {
      options.enqueueTypewriterText(message, `请求失败：${text}`)
      options.markDoneAfterTypewriter(message)
    }
    options.errorText.value = text
    options.markPetError()
    options.setCurrentPhase('error')
  }

  async function finalizeRequest(message: UiMessage) {
    options.isSending.value = false
    clearActiveRequest(message)

    try {
      await options.refreshConversations()
    } catch (error) {
      options.errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function runChatRequest(
    content: string,
    model: string,
    assistantMessage: UiMessage,
    editMessageId?: string,
    history?: ChatMessage[],
    userMessage?: UiMessage,
  ) {
    if (options.isSending.value) {
      return
    }

    abortController = new AbortController()
    activeAssistantMessage = assistantMessage
    options.isSending.value = true
    options.errorText.value = ''
    assistantMessage.phase = 'thinking'
    assistantMessage.status = 'running'
    assistantMessage.isStreaming = true
    options.applyStreamPhase('thinking', assistantMessage)

    try {
      await streamChat(
        {
          message: content,
          realtimeSearch: options.realtimeSearch.value,
          model,
          history,
          conversationId: options.currentConversationId.value,
          editMessageId,
        },
        {
          onEvent: (event) => options.applyStreamEvent(event, { assistantMessage, userMessage }),
        },
        abortController.signal,
      )
    } catch (error) {
      if (isAbortError(error) || abortController.signal.aborted) {
        options.markMessageCancelled(assistantMessage)
      } else {
        failMessage(assistantMessage, error)
      }
    } finally {
      await finalizeRequest(assistantMessage)
    }
  }

  async function handleToolDecision(message: UiMessage, decision: 'approved' | 'denied') {
    if (options.isSending.value || !message.agentRunId) {
      return
    }

    abortController = new AbortController()
    activeAssistantMessage = message
    options.isSending.value = true
    options.errorText.value = ''
    message.pendingTool = undefined
    message.phase = 'thinking'
    message.status = 'running'
    message.isStreaming = true
    if (decision === 'approved') {
      options.realtimeSearch.value = true
    }
    options.applyStreamPhase('thinking', message)

    try {
      await streamChat(
        {
          message: message.content,
          realtimeSearch: decision === 'approved',
          model: message.model || options.selectedModel.value,
          conversationId: options.currentConversationId.value,
          agentRunId: message.agentRunId,
          toolDecision: decision,
        },
        {
          onEvent: (event) => options.applyStreamEvent(event, { assistantMessage: message }),
        },
        abortController.signal,
      )
    } catch (error) {
      if (isAbortError(error) || abortController.signal.aborted) {
        options.markMessageCancelled(message)
      } else {
        failMessage(message, error)
      }
    } finally {
      if (!isCancelledMessage(message) && !isWaitingForToolConfirmation(message)) {
        try {
          await options.refreshCurrentConversationMessages()
        } catch (error) {
          options.errorText.value = error instanceof Error ? error.message : String(error)
        }
      }
      await finalizeRequest(message)
    }
  }

  function stopGeneration() {
    const target = activeAssistantMessage || lastActiveAssistant(options.messages.value)
    if (!target) {
      options.isSending.value = false
      return
    }

    if (target.agentRunId) {
      void cancelAgentRun(target.agentRunId, responseSnapshot(target)).catch((error) => {
        options.errorText.value = error instanceof Error ? error.message : String(error)
      })
    }

    abortController?.abort()
    options.markMessageCancelled(target)
    options.isSending.value = false
    clearActiveRequest(target)
  }

  function dispose() {
    abortController?.abort()
    abortController = undefined
    activeAssistantMessage = undefined
  }

  return {
    runChatRequest,
    handleToolDecision,
    stopGeneration,
    dispose,
  }
}
