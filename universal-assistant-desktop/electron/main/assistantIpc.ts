import { ipcMain } from 'electron'
import { BACKEND_URL } from './constants'
import type { PetMotionPayload, PetState } from './types'

export function registerAssistantIpcHandlers(handlers: {
  chat: {
    hideChatWindowAnimated: () => void
    showChatWindow: () => void
    toggleChatWindow: () => void
  }
  pet: {
    movePetWindowBy: (deltaX: unknown, deltaY: unknown) => void
    performPetMotion: (payload?: PetMotionPayload) => void
    setPetPointerActive: (active: unknown) => void
    setPetState: (state: unknown) => void
    showPetMenu: () => void
  }
}) {
  ipcMain.handle('assistant:toggle-chat', () => handlers.chat.toggleChatWindow())
  ipcMain.handle('assistant:show-chat', () => handlers.chat.showChatWindow())
  ipcMain.handle('assistant:hide-chat', () => {
    handlers.pet.setPetState('idle' satisfies PetState)
    handlers.chat.hideChatWindowAnimated()
  })
  ipcMain.handle('assistant:get-backend-url', () => BACKEND_URL)
  ipcMain.handle('assistant:set-pet-state', (_event, state: PetState) => handlers.pet.setPetState(state))
  ipcMain.handle('assistant:set-pet-pointer-active', (_event, active: boolean) =>
    handlers.pet.setPetPointerActive(active),
  )
  ipcMain.handle('assistant:show-pet-menu', () => handlers.pet.showPetMenu())
  ipcMain.handle('assistant:perform-pet-motion', (_event, payload?: PetMotionPayload) =>
    handlers.pet.performPetMotion(payload),
  )
  ipcMain.handle('assistant:move-pet-by', (_event, payload: { deltaX?: unknown; deltaY?: unknown }) =>
    handlers.pet.movePetWindowBy(payload?.deltaX, payload?.deltaY),
  )
}
