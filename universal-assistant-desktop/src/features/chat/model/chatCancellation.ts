import type { UiMessage, UiPhase } from './chatTypes'

export function markMessageCancelled(options: {
  message: UiMessage
  flushTypewriter: (message: UiMessage) => void
  markPreviousUserMessageEditable: (message: UiMessage) => void
  setCurrentPhase: (phase: UiPhase) => void
  schedulePetIdle: (delay?: number) => void
  resetCurrentPhaseLater: (delay?: number) => void
  scrollMessagesToBottom: () => void
}) {
  const { message } = options

  options.flushTypewriter(message)
  message.phase = 'cancelled'
  message.status = 'cancelled'
  message.isStreaming = false
  message.pendingTool = undefined
  message.activeMarkdownBlockId = undefined
  message.contentBlocks = (message.contentBlocks || []).map((block) =>
    block.type === 'markdown' ? { ...block, streaming: false } : block,
  )

  options.markPreviousUserMessageEditable(message)
  options.setCurrentPhase('cancelled')
  options.schedulePetIdle(900)
  options.resetCurrentPhaseLater(2200)
  options.scrollMessagesToBottom()
}
