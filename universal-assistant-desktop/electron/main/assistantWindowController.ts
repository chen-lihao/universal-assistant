import { BrowserWindow } from 'electron'
import { registerAssistantIpcHandlers } from './assistantIpc'
import { createChatWindowController } from './chatWindowController'
import { createFireworkEffectController } from './fireworkEffectController'
import { createPetWindowController } from './petWindowController'
import { createAssistantWindowState } from './types'
import { createWindowMotion } from './windowMotion'

export function createAssistantWindowController() {
  const state = createAssistantWindowState()

  function suppressMoveSyncFor(window: BrowserWindow) {
    if (window === state.petWindow) {
      state.suppressPetMoveSync = true
      if (state.releasePetMoveSyncTimer) {
        clearTimeout(state.releasePetMoveSyncTimer)
      }

      state.releasePetMoveSyncTimer = setTimeout(() => {
        state.suppressPetMoveSync = false
        state.releasePetMoveSyncTimer = null
      }, 32)
      return
    }

    if (window === state.chatWindow) {
      state.suppressChatMoveSync = true
      if (state.releaseChatMoveSyncTimer) {
        clearTimeout(state.releaseChatMoveSyncTimer)
      }

      state.releaseChatMoveSyncTimer = setTimeout(() => {
        state.suppressChatMoveSync = false
        state.releaseChatMoveSyncTimer = null
      }, 32)
    }
  }

  function clearMoveSyncSuppressions() {
    if (state.releasePetMoveSyncTimer) {
      clearTimeout(state.releasePetMoveSyncTimer)
      state.releasePetMoveSyncTimer = null
    }
    if (state.releaseChatMoveSyncTimer) {
      clearTimeout(state.releaseChatMoveSyncTimer)
      state.releaseChatMoveSyncTimer = null
    }
    state.suppressPetMoveSync = false
    state.suppressChatMoveSync = false
  }

  const windowMotion = createWindowMotion({ beforeWindowChange: suppressMoveSyncFor })
  const fireworkEffect = createFireworkEffectController()

  const chatControllerRef: { current?: ReturnType<typeof createChatWindowController> } = {}
  const petController = createPetWindowController({
    state,
    windowMotion,
    clearMoveSyncSuppressions,
    positionChatWindow: () => chatControllerRef.current?.positionChatWindow(),
    launchFireworks: () => fireworkEffect.launch(state.petWindow),
    onPetClosed: fireworkEffect.dispose,
  })

  const chatController = createChatWindowController({
    state,
    windowMotion,
    clearMoveSyncSuppressions,
    pet: {
      cancelPetRoam: petController.cancelPetRoam,
      schedulePetRoam: petController.schedulePetRoam,
      setPetState: petController.setPetState,
      stopPetFollow: petController.stopPetFollow,
      updatePetFollowTarget: petController.updatePetFollowTarget,
    },
  })
  chatControllerRef.current = chatController

  function registerIpcHandlers() {
    registerAssistantIpcHandlers({
      chat: {
        hideChatWindowAnimated: chatController.hideChatWindowAnimated,
        showChatWindow: chatController.showChatWindow,
        toggleChatWindow: chatController.toggleChatWindow,
      },
      pet: {
        movePetWindowBy: petController.movePetWindowBy,
        performPetMotion: petController.performPetMotion,
        setPetPointerActive: petController.setPetPointerActive,
        setPetState: petController.setPetState,
        showPetMenu: petController.showPetMenu,
      },
    })
  }

  function handleWindowAllClosed() {
    fireworkEffect.dispose()
    state.petWindow = null
    state.chatWindow = null
  }

  function handleActivate() {
    if (BrowserWindow.getAllWindows().length === 0) {
      petController.createPetWindow()
      chatController.createChatWindow()
    }
  }

  return {
    createPetWindow: petController.createPetWindow,
    createChatWindow: chatController.createChatWindow,
    registerIpcHandlers,
    handleWindowAllClosed,
    handleActivate,
  }
}
