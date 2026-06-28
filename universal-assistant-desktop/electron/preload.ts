import { ipcRenderer, contextBridge } from 'electron'
import type { IpcRendererEvent } from 'electron'

type PetState = 'idle' | 'thinking' | 'speaking' | 'happy' | 'curious' | 'sleepy' | 'running' | 'error'

contextBridge.exposeInMainWorld('assistant', {
  toggleChat: () => ipcRenderer.invoke('assistant:toggle-chat'),
  hideChat: () => ipcRenderer.invoke('assistant:hide-chat'),
  getBackendUrl: () => ipcRenderer.invoke('assistant:get-backend-url'),
  setPetState: (state: PetState) => ipcRenderer.invoke('assistant:set-pet-state', state),
  setPetPointerActive: (active: boolean) => ipcRenderer.invoke('assistant:set-pet-pointer-active', active),
  movePetBy: (payload: { deltaX: number; deltaY: number }) => ipcRenderer.invoke('assistant:move-pet-by', payload),
  onPetState: (callback: (state: PetState) => void) => {
    const listener = (_event: IpcRendererEvent, state: PetState) => callback(state)
    ipcRenderer.on('assistant:pet-state', listener)

    return () => ipcRenderer.removeListener('assistant:pet-state', listener)
  },
  files: {
    select: (options?: { directory?: boolean; multiple?: boolean }) => ipcRenderer.invoke('file:select', options),
    readText: (filePath: string) => ipcRenderer.invoke('file:read-text', filePath),
    writeText: (payload: { filePath: string; content: string }) => ipcRenderer.invoke('file:write-text', payload),
    saveTextAs: (payload: { defaultPath?: string; content: string }) => ipcRenderer.invoke('file:save-text-as', payload),
    convertText: (payload: { filePath: string; targetFormat: string }) =>
      ipcRenderer.invoke('file:convert-text', payload),
  },
})
