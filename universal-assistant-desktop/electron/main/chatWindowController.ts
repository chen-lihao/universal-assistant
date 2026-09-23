import { screen } from 'electron'
import type { BrowserWindow } from 'electron'
import {
  CHAT_COLLAPSED_SIZE,
  CHAT_TRANSITION_MS,
  CHAT_WINDOW_MIN_HEIGHT,
  CHAT_WINDOW_MIN_WIDTH,
  WINDOW_GAP,
} from './constants'
import type { createWindowMotion, WindowBounds, WindowPoint } from './windowMotion'
import { clamp } from './windowMotion'
import type { AssistantWindowState, PetState } from './types'
import { createChatBrowserWindow } from './windowFactory'

type WindowMotion = ReturnType<typeof createWindowMotion>

export function createChatWindowController(options: {
  state: AssistantWindowState
  windowMotion: WindowMotion
  clearMoveSyncSuppressions: () => void
  pet: {
    cancelPetRoam: () => void
    stopPetFollow: () => void
    schedulePetRoam: () => void
    updatePetFollowTarget: () => void
    setPetState: (state: PetState) => void
  }
}) {
  const { state, windowMotion, pet } = options

  function getChatWindowPosition(): WindowPoint | null {
    if (!state.petWindow || !state.chatWindow) {
      return null
    }

    const petBounds = state.petWindow.getBounds()
    const chatBounds = state.chatWindow.getBounds()
    const targetWorkArea = screen.getDisplayMatching(petBounds).workArea
    let x = petBounds.x - chatBounds.width - WINDOW_GAP
    if (x < targetWorkArea.x) {
      x = petBounds.x + petBounds.width + WINDOW_GAP
    }

    const y = clamp(
      petBounds.y + petBounds.height - chatBounds.height,
      targetWorkArea.y + WINDOW_GAP,
      targetWorkArea.y + targetWorkArea.height - chatBounds.height - WINDOW_GAP,
    )

    return {
      x: clamp(
        x,
        targetWorkArea.x + WINDOW_GAP,
        targetWorkArea.x + targetWorkArea.width - chatBounds.width - WINDOW_GAP,
      ),
      y,
    }
  }

  function getChatWindowTargetBounds(): WindowBounds | null {
    if (!state.chatWindow) {
      return null
    }

    const position = getChatWindowPosition()
    if (!position) {
      return null
    }

    const bounds = state.chatWindow.getBounds()
    const width = Math.max(bounds.width, CHAT_WINDOW_MIN_WIDTH)
    const height = Math.max(bounds.height, CHAT_WINDOW_MIN_HEIGHT)
    return {
      x: position.x,
      y: position.y,
      width,
      height,
    }
  }

  function getChatCollapsedBounds(): WindowBounds | null {
    if (!state.petWindow || !state.chatWindow) {
      return null
    }

    const petBounds = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(petBounds).workArea
    const size = CHAT_COLLAPSED_SIZE
    return {
      x: Math.round(
        clamp(
          petBounds.x + petBounds.width / 2 - size / 2,
          workArea.x + WINDOW_GAP,
          workArea.x + workArea.width - size - WINDOW_GAP,
        ),
      ),
      y: Math.round(
        clamp(
          petBounds.y + petBounds.height / 2 - size / 2,
          workArea.y + WINDOW_GAP,
          workArea.y + workArea.height - size - WINDOW_GAP,
        ),
      ),
      width: size,
      height: size,
    }
  }

  function positionChatWindow(positionOptions: { animated?: boolean } = {}) {
    if (!state.chatWindow) {
      return
    }

    const position = getChatWindowPosition()
    if (!position) {
      return
    }

    windowMotion.moveWindowTo(state.chatWindow, position.x, position.y, Boolean(positionOptions.animated))
  }

  function showChatWindowAnimated() {
    if (!state.chatWindow) {
      return
    }

    if (state.chatWindow.isVisible() && state.chatTransition !== 'hiding') {
      state.chatLogicalVisible = true
      state.chatWindow.focus()
      return
    }

    if (state.chatTransition === 'showing') {
      return
    }

    const target = getChatWindowTargetBounds()
    const collapsed = getChatCollapsedBounds()
    if (!target || !collapsed) {
      return
    }

    windowMotion.cancelWindowAnimation(state.chatWindow)
    windowMotion.cancelWindowOpacityAnimation(state.chatWindow)
    state.chatTransition = 'showing'
    state.chatLogicalVisible = true
    state.chatWindow.setMinimumSize(1, 1)

    if (!state.chatWindow.isVisible()) {
      windowMotion.setWindowBounds(state.chatWindow, collapsed)
      state.chatWindow.setOpacity(0.72)
      state.chatWindow.show()
    }

    state.chatWindow.focus()
    windowMotion.animateWindowBounds(state.chatWindow, target, CHAT_TRANSITION_MS, () => {
      if (!state.chatWindow || state.chatWindow.isDestroyed()) {
        return
      }

      state.chatWindow.setMinimumSize(CHAT_WINDOW_MIN_WIDTH, CHAT_WINDOW_MIN_HEIGHT)
      state.chatTransition = null
    })
    windowMotion.animateWindowOpacity(state.chatWindow, 1, CHAT_TRANSITION_MS)
  }

  function hideChatWindowAnimated() {
    state.chatLogicalVisible = false
    if (!state.chatWindow || !state.chatWindow.isVisible()) {
      return
    }

    if (state.chatTransition === 'hiding') {
      return
    }

    const start = state.chatWindow.getBounds()
    const collapsed = getChatCollapsedBounds()
    if (!collapsed) {
      state.chatWindow.hide()
      state.chatLogicalVisible = false
      return
    }

    windowMotion.cancelWindowAnimation(state.chatWindow)
    windowMotion.cancelWindowOpacityAnimation(state.chatWindow)
    state.chatTransition = 'hiding'
    state.chatWindow.setMinimumSize(1, 1)
    windowMotion.animateWindowBounds(state.chatWindow, collapsed, CHAT_TRANSITION_MS, () => {
      if (!state.chatWindow || state.chatWindow.isDestroyed()) {
        return
      }

      state.chatWindow.hide()
      windowMotion.setWindowBounds(state.chatWindow, {
        x: start.x,
        y: start.y,
        width: Math.max(start.width, CHAT_WINDOW_MIN_WIDTH),
        height: Math.max(start.height, CHAT_WINDOW_MIN_HEIGHT),
      })
      state.chatWindow.setMinimumSize(CHAT_WINDOW_MIN_WIDTH, CHAT_WINDOW_MIN_HEIGHT)
      state.chatWindow.setOpacity(1)
      state.chatTransition = null
    })
    windowMotion.animateWindowOpacity(state.chatWindow, 0.18, CHAT_TRANSITION_MS)
  }

  function syncPetWindowToChatWindow() {
    if (state.suppressChatMoveSync || !state.chatWindow?.isVisible()) {
      return
    }

    pet.updatePetFollowTarget()
  }

  function createChatWindow() {
    state.chatWindow = createChatBrowserWindow({
      onShow: () => {
        pet.cancelPetRoam()
        pet.stopPetFollow()
      },
      onHide: () => {
        state.chatLogicalVisible = false
        pet.stopPetFollow()
        pet.schedulePetRoam()
      },
      onBeforeInput: (event, input) => {
        if (input.key === 'Escape' && input.type === 'keyDown' && state.chatLogicalVisible) {
          event.preventDefault()
          pet.setPetState('idle')
          hideChatWindowAnimated()
        }
      },
      onMove: syncPetWindowToChatWindow,
      onResize: syncPetWindowToChatWindow,
      onClosed: (window: BrowserWindow) => {
        pet.stopPetFollow()
        options.clearMoveSyncSuppressions()
        state.chatTransition = null
        windowMotion.cancelWindowAnimation(window)
        windowMotion.cancelWindowOpacityAnimation(window)
        state.chatWindow = null
        pet.schedulePetRoam()
      },
    })
  }

  function showChatWindow() {
    if (!state.chatWindow) {
      createChatWindow()
    }

    if (!state.chatWindow) {
      return
    }

    pet.cancelPetRoam()
    pet.stopPetFollow()
    pet.setPetState('idle')
    showChatWindowAnimated()
  }

  function toggleChatWindow() {
    if (!state.chatWindow) {
      createChatWindow()
    }

    if (!state.chatWindow) {
      return
    }

    if (state.chatTransition === 'showing') {
      return
    }

    if (state.chatTransition === 'hiding') {
      showChatWindow()
      return
    }

    if (state.chatLogicalVisible && state.chatWindow.isFocused()) {
      pet.setPetState('idle')
      hideChatWindowAnimated()
      return
    }

    showChatWindow()
  }

  return {
    createChatWindow,
    hideChatWindowAnimated,
    positionChatWindow,
    showChatWindow,
    toggleChatWindow,
  }
}
