import type { Ref } from 'vue'
import type { ChatStreamEvent, ChatStreamPhase, MessageBlock } from '../../../services/assistantApi'
import { mergeSources, type UiMessage, type UiPhase } from './chatTypes'
import { syncExecutionBlock, syncSourcesBlock, upsertContentBlock } from './messageBlocks'
import { canFinishMessage } from './chatStreamUtils'

type StreamTarget = {
  assistantMessage: UiMessage
  userMessage?: UiMessage
}

type StreamMessageState = {
  blockProtocolActive: boolean
  legacyDeltaIntoBlock: boolean
}

type EventApplierOptions = {
  currentConversationId: Ref<string | undefined>
  setCurrentPhase: (phase: UiPhase) => void
  clearTypewriterFor: (message: UiMessage) => void
  enqueueTypewriterText: (message: UiMessage, content: string) => void
  flushTypewriter: (message: UiMessage) => void
  markDoneAfterTypewriter: (message: UiMessage) => void
  markPetSpeaking: (delay?: number) => void
  markPetError: () => void
  setPetState: (state: PetState) => void
  scrollMessagesToBottom: () => void
  getMessage: (messageId: UiMessage['id']) => UiMessage | undefined
  patchMessage: (messageId: UiMessage['id'], updater: (message: UiMessage) => void) => UiMessage | undefined
}

const streamStates = new WeakMap<UiMessage, StreamMessageState>()

function stringId(value: unknown) {
  return value === undefined || value === null ? undefined : String(value)
}

function streamState(message: UiMessage) {
  let state = streamStates.get(message)
  if (!state) {
    state = { blockProtocolActive: false, legacyDeltaIntoBlock: false }
    streamStates.set(message, state)
  }
  return state
}

function phaseForStatus(phase?: ChatStreamPhase): UiPhase | undefined {
  return phase
}

function phasePetState(phase: UiPhase): PetState | undefined {
  if (phase === 'answering') {
    return 'speaking'
  }
  if (
    phase === 'thinking' ||
    phase === 'planning' ||
    phase === 'acting' ||
    phase === 'searching' ||
    phase === 'reflecting'
  ) {
    return 'thinking'
  }
  if (phase === 'waiting_confirmation') {
    return 'curious'
  }
  if (phase === 'error') {
    return 'error'
  }
  return undefined
}

function ensureMarkdownBlock(message: UiMessage, blockId?: string) {
  const id = blockId || message.activeMarkdownBlockId || 'runtime-markdown'
  const existing = (message.contentBlocks || []).find((block) => block.id === id && block.type === 'markdown')
  message.activeMarkdownBlockId = id
  if (!existing) {
    upsertContentBlock(message, {
      id,
      type: 'markdown',
      content: '',
      streaming: true,
    })
    placeMarkdownBeforeTailBlocks(message, id)
  }
  return id
}

function placeMarkdownBeforeTailBlocks(message: UiMessage, blockId: string) {
  const blocks = message.contentBlocks || []
  const markdownBlock = blocks.find((block) => block.id === blockId && block.type === 'markdown')
  if (!markdownBlock) {
    return
  }

  const rest = blocks.filter((block) => block.id !== blockId)
  const tailIndex = rest.findIndex(
    (block) => block.type === 'sources' || block.type === 'status' || block.type === 'error',
  )
  message.contentBlocks =
    tailIndex >= 0 ? [...rest.slice(0, tailIndex), markdownBlock, ...rest.slice(tailIndex)] : [...rest, markdownBlock]
}

function markMarkdownBlockEnded(message: UiMessage, blockId?: string) {
  if (!blockId) {
    return
  }

  message.contentBlocks = (message.contentBlocks || []).map((block) =>
    block.id === blockId && block.type === 'markdown' ? { ...block, streaming: false } : block,
  )
  if (message.activeMarkdownBlockId === blockId) {
    message.activeMarkdownBlockId = undefined
  }
}

function resetAnswerContent(message: UiMessage) {
  message.content = ''
  message.activeMarkdownBlockId = `runtime-markdown-${Date.now()}`
  message.contentBlocks = (message.contentBlocks || []).filter(
    (block) => block.type !== 'markdown' && block.type !== 'status' && block.type !== 'error',
  )
  upsertContentBlock(message, {
    id: message.activeMarkdownBlockId,
    type: 'markdown',
    content: '',
    streaming: true,
  })
}

function toMessageBlock(block: MessageBlock | undefined) {
  return block
}

export function createChatStreamEventApplier(options: EventApplierOptions) {
  function updateMessage(message: UiMessage, updater: (message: UiMessage) => void) {
    return options.patchMessage(message.id, updater) || message
  }

  function currentMessage(message: UiMessage) {
    return options.getMessage(message.id) || message
  }

  function applyStreamPhase(phase: UiPhase, message?: UiMessage) {
    if (message) {
      updateMessage(message, (currentMessage) => {
        currentMessage.phase = phase === 'idle' ? undefined : phase
      })
    }
    options.setCurrentPhase(phase)

    const petState = phasePetState(phase)
    if (petState === 'speaking') {
      options.markPetSpeaking(0)
    } else if (petState === 'error') {
      options.markPetError()
    } else if (petState) {
      options.setPetState(petState)
    }
  }

  function applyMeta(event: ChatStreamEvent, target: StreamTarget) {
    const { assistantMessage, userMessage } = target
    const conversationId = stringId(event.conversationId)
    const assistantMessageId = stringId(event.messageId)
    const userMessageId = stringId(event.userMessageId)
    const agentRunId = stringId(event.agentRunId)

    if (conversationId) {
      options.currentConversationId.value = conversationId
    }
    updateMessage(assistantMessage, (message) => {
      if (assistantMessageId) {
        message.messageId = assistantMessageId
      }
      if (agentRunId) {
        message.agentRunId = agentRunId
      }
      if (event.model) {
        message.model = event.model
      }
      if (event.modelAvailable !== undefined) {
        message.modelAvailable = event.modelAvailable
      }
      if (event.realtimeSearchUsed !== undefined) {
        message.realtimeSearchUsed = event.realtimeSearchUsed
      }
      if (event.sources?.length) {
        message.sources = mergeSources(message.sources, event.sources)
        syncSourcesBlock(message)
      }
    })
    if (userMessage && userMessageId) {
      updateMessage(userMessage, (message) => {
        message.messageId = userMessageId
        message.localEditable = true
      })
    }
  }

  function applyAgentStep(event: ChatStreamEvent, message: UiMessage) {
    const agentStep = event.agentStep
    if (!agentStep) {
      return
    }

    updateMessage(message, (currentMessage) => {
      const steps = currentMessage.agentSteps || []
      const existingIndex = steps.findIndex((step) => step.id === agentStep.id)
      currentMessage.agentSteps =
        existingIndex >= 0
          ? steps.map((step, index) => (index === existingIndex ? agentStep : step))
          : [...steps, agentStep].sort((left, right) => left.index - right.index)
      if (event.agentRunId) {
        currentMessage.agentRunId = String(event.agentRunId)
      }
      syncExecutionBlock(currentMessage)
    })
  }

  function applyToolConfirmation(event: ChatStreamEvent, message: UiMessage) {
    updateMessage(message, (currentMessage) => {
      currentMessage.phase = 'waiting_confirmation'
      currentMessage.status = 'waiting_confirmation'
      currentMessage.isStreaming = false
      currentMessage.agentRunId = stringId(event.agentRunId) || currentMessage.agentRunId
      currentMessage.pendingTool = {
        name: event.toolName || 'web_search',
        input: event.toolInput || '',
        reason: event.reason || 'Agent 判断需要实时检索，请确认是否允许。',
      }
    })
    applyStreamPhase('waiting_confirmation', message)
    options.scrollMessagesToBottom()
  }

  function applyBlockStart(event: ChatStreamEvent, message: UiMessage) {
    const block = toMessageBlock(event.block)
    if (!block) {
      return
    }

    const state = streamState(message)
    state.blockProtocolActive = true
    state.legacyDeltaIntoBlock = false

    updateMessage(message, (currentMessage) => {
      if (block.type === 'markdown') {
        currentMessage.activeMarkdownBlockId = block.id
      }
      upsertContentBlock(currentMessage, block)
      if (block.type === 'markdown') {
        placeMarkdownBeforeTailBlocks(currentMessage, block.id)
      }
    })
    options.scrollMessagesToBottom()
  }

  function applyBlockDelta(event: ChatStreamEvent, message: UiMessage) {
    if (!event.content) {
      return
    }

    const state = streamState(message)
    state.blockProtocolActive = true
    state.legacyDeltaIntoBlock = false
    const currentMessage = updateMessage(message, (messageToUpdate) => {
      ensureMarkdownBlock(messageToUpdate, event.blockId)
    })
    options.enqueueTypewriterText(currentMessage, event.content)
  }

  function applyLegacyDelta(event: ChatStreamEvent, message: UiMessage) {
    if (!event.content) {
      return
    }

    const state = streamState(message)
    if (state.blockProtocolActive && !state.legacyDeltaIntoBlock) {
      return
    }

    const currentMessage = updateMessage(message, (messageToUpdate) => {
      ensureMarkdownBlock(messageToUpdate)
    })
    options.enqueueTypewriterText(currentMessage, event.content)
  }

  function applyError(event: ChatStreamEvent, message: UiMessage) {
    options.flushTypewriter(message)
    updateMessage(message, (currentMessage) => {
      currentMessage.phase = 'error'
      currentMessage.status = 'failed'
      currentMessage.isStreaming = false
      currentMessage.modelAvailable = false
      upsertContentBlock(currentMessage, {
        id: `runtime-error-${Date.now()}`,
        type: 'error',
        message: event.message || '请求失败',
        recoverable: true,
      })
    })
    applyStreamPhase('error', message)
    options.scrollMessagesToBottom()
  }

  function applyStreamEvent(event: ChatStreamEvent, target: StreamTarget) {
    const { assistantMessage } = target

    if (event.type === 'meta') {
      applyMeta(event, target)
      return
    }

    if (event.type !== 'done' && event.phase) {
      const phase = phaseForStatus(event.phase)
      if (phase) {
        applyStreamPhase(phase, assistantMessage)
      }
    }

    switch (event.type) {
      case 'status':
        updateMessage(assistantMessage, (message) => {
          message.isStreaming = event.phase !== 'waiting_confirmation'
        })
        break
      case 'agent_step':
        applyAgentStep(event, assistantMessage)
        options.scrollMessagesToBottom()
        break
      case 'tool_confirmation_required':
        applyToolConfirmation(event, assistantMessage)
        break
      case 'answer_reset': {
        options.flushTypewriter(assistantMessage)
        updateMessage(assistantMessage, resetAnswerContent)
        const state = streamState(assistantMessage)
        state.blockProtocolActive = false
        state.legacyDeltaIntoBlock = true
        applyStreamPhase('answering', assistantMessage)
        break
      }
      case 'block_start':
        applyBlockStart(event, assistantMessage)
        break
      case 'block_delta':
        applyBlockDelta(event, assistantMessage)
        break
      case 'block_end':
        updateMessage(assistantMessage, (message) => {
          markMarkdownBlockEnded(message, event.blockId)
        })
        break
      case 'delta':
        applyLegacyDelta(event, assistantMessage)
        break
      case 'error':
        applyError(event, assistantMessage)
        break
      case 'done':
        if (canFinishMessage(currentMessage(assistantMessage))) {
          const updatedMessage = updateMessage(assistantMessage, (message) => {
            message.status = 'completed'
            message.pendingTool = undefined
          })
          options.markDoneAfterTypewriter(updatedMessage)
        }
        break
    }
  }

  return {
    applyStreamEvent,
    applyStreamPhase,
  }
}
