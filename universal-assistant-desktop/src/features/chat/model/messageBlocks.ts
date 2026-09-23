import type { MessageBlock } from '../../../services/assistantApi'
import type { UiMessage } from './chatTypes'

export function appendAssistantText(message: UiMessage, text: string) {
  if (!text) {
    return
  }

  message.content += text
  const markdownBlock = currentMarkdownBlock(message)
  if (markdownBlock) {
    markdownBlock.content += text
  }
}

export function currentMarkdownBlock(message: UiMessage) {
  const blockId = message.activeMarkdownBlockId
  const block = (message.contentBlocks || []).find(
    (item) => item.type === 'markdown' && (!blockId || item.id === blockId),
  )
  return block?.type === 'markdown' ? block : undefined
}

export function upsertContentBlock(message: UiMessage, block: MessageBlock) {
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

export function syncExecutionBlock(message: UiMessage) {
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

export function syncSourcesBlock(message: UiMessage) {
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
