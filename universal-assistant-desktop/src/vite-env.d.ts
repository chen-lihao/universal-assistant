/// <reference types="vite/client" />

type FileSelectResult = {
  canceled: boolean
  paths: string[]
}

type TextFileResult = {
  path: string
  name: string
  size: number
  content: string
}

type SaveTextResult = {
  canceled?: boolean
  path?: string
  name?: string
}

type ConvertTextResult = {
  sourcePath: string
  defaultPath: string
  content: string
  targetFormat: string
}

type PetState = 'idle' | 'thinking' | 'speaking' | 'happy' | 'curious' | 'sleepy' | 'running' | 'error'

interface Window {
  assistant?: {
    toggleChat: () => Promise<void>
    hideChat: () => Promise<void>
    getBackendUrl: () => Promise<string>
    setPetState: (state: PetState) => Promise<void>
    setPetPointerActive: (active: boolean) => Promise<void>
    movePetBy: (payload: { deltaX: number; deltaY: number }) => Promise<void>
    onPetState: (callback: (state: PetState) => void) => () => void
    files: {
      select: (options?: { directory?: boolean; multiple?: boolean }) => Promise<FileSelectResult>
      readText: (filePath: string) => Promise<TextFileResult>
      writeText: (payload: { filePath: string; content: string }) => Promise<{ path: string; name: string }>
      saveTextAs: (payload: { defaultPath?: string; content: string }) => Promise<SaveTextResult>
      convertText: (payload: { filePath: string; targetFormat: string }) => Promise<ConvertTextResult>
    }
  }
}
