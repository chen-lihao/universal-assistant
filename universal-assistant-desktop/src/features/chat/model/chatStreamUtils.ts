import type { UiMessage } from './chatTypes'

export function isCancelledMessage(message: UiMessage) {
  return message.status === 'cancelled' || message.phase === 'cancelled'
}

export function isWaitingForToolConfirmation(message: UiMessage) {
  return (
    message.status === 'waiting_confirmation' ||
    message.phase === 'waiting_confirmation' ||
    Boolean(message.pendingTool)
  )
}

export function isFailedMessage(message: UiMessage) {
  return message.status === 'failed' || message.phase === 'error'
}

export function canFinishMessage(message: UiMessage) {
  return !isCancelledMessage(message) && !isWaitingForToolConfirmation(message) && !isFailedMessage(message)
}
