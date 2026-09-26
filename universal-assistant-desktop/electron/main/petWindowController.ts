import type { BrowserWindow } from 'electron'
import type { createWindowMotion } from './windowMotion'
import type { AssistantWindowState, PetAction, PetState } from './types'
import { createPetBrowserWindow } from './windowFactory'
import { createPetMotionController } from './petMotionController'
import { showPetInteractionMenu } from './petMenu'

type WindowMotion = ReturnType<typeof createWindowMotion>

export function createPetWindowController(options: {
  state: AssistantWindowState
  windowMotion: WindowMotion
  clearMoveSyncSuppressions: () => void
  positionChatWindow: () => void
  launchFireworks: () => void
  onPetClosed: () => void
}) {
  const { state, windowMotion } = options

  function sendCurrentPetState() {
    if (!state.petWindow || state.petWindow.isDestroyed()) {
      return
    }

    state.petWindow.webContents.send('assistant:pet-state', state.currentPetState)
  }

  function sendPetAction(action: PetAction) {
    if (!state.petWindow || state.petWindow.isDestroyed()) {
      return
    }

    state.petWindow.webContents.send('assistant:pet-action', action)
  }

  function setPetState(nextState: unknown) {
    if (
      nextState !== 'idle' &&
      nextState !== 'thinking' &&
      nextState !== 'speaking' &&
      nextState !== 'happy' &&
      nextState !== 'curious' &&
      nextState !== 'sleepy' &&
      nextState !== 'running' &&
      nextState !== 'error'
    ) {
      return
    }

    state.currentPetState = nextState
    sendCurrentPetState()
  }

  const motion = createPetMotionController({
    state,
    windowMotion,
    setPetState: (nextState: PetState) => setPetState(nextState),
    positionChatWindow: options.positionChatWindow,
  })

  function syncChatWindowToPetWindow() {
    if (state.suppressPetMoveSync || !state.chatWindow?.isVisible()) {
      return
    }

    options.positionChatWindow()
  }

  function runPetMenuAction(action: PetAction) {
    sendPetAction(action)

    if (action === 'fireworks') {
      motion.cancelPetRoam()
      motion.stopPetFollow()
      state.petManualControlUntil = Date.now() + 2600
      options.launchFireworks()
    }

    if (action === 'jump' || action === 'run') {
      motion.setPetPointerActive(false)
      setTimeout(() => motion.performPetInteractionMotion(action), 30)
      return
    }

    if (action === 'idle') {
      setPetState('idle')
    } else if (action === 'sleep') {
      setPetState('sleepy')
    }

    if (!state.chatWindow?.isVisible()) {
      motion.schedulePetRoam()
    }
  }

  function showPetMenu() {
    if (!state.petWindow || state.petWindow.isDestroyed()) {
      return
    }

    showPetInteractionMenu({
      petWindow: state.petWindow,
      runAction: runPetMenuAction,
      onClose: () => motion.setPetPointerActive(false),
    })
  }

  function createPetWindow() {
    state.petWindow = createPetBrowserWindow({
      onReadyToShow: () => {
        state.petWindow?.show()
        motion.schedulePetRoam()
      },
      onDidFinishLoad: sendCurrentPetState,
      onMove: syncChatWindowToPetWindow,
      onClosed: (window: BrowserWindow) => {
        options.onPetClosed()
        motion.stopPetFollow()
        motion.cancelPetRoam()
        windowMotion.cancelWindowOpacityAnimation(window)
        options.clearMoveSyncSuppressions()
        state.petWindow = null
      },
    })
  }

  return {
    cancelPetRoam: motion.cancelPetRoam,
    createPetWindow,
    movePetWindowBy: motion.movePetWindowBy,
    performPetMotion: motion.performPetMotion,
    schedulePetRoam: motion.schedulePetRoam,
    setPetPointerActive: motion.setPetPointerActive,
    setPetState,
    showPetMenu,
    stopPetFollow: motion.stopPetFollow,
    updatePetFollowTarget: motion.updatePetFollowTarget,
  }
}
