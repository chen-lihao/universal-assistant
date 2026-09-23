import type { BrowserWindow } from 'electron'
import type { WindowPoint } from './windowMotion'

export type PetState = 'idle' | 'thinking' | 'speaking' | 'happy' | 'curious' | 'sleepy' | 'running' | 'error'
export type PetAction = 'wave' | 'jump' | 'fireworks' | 'run' | 'sleep' | 'idle'
export type ChatTransition = 'showing' | 'hiding' | null

export type PetMotionPayload = {
  type?: string
  rangeX?: number
  rangeY?: number
  duration?: number
  force?: boolean
}

export type AssistantWindowState = {
  petWindow: BrowserWindow | null
  chatWindow: BrowserWindow | null
  currentPetState: PetState
  suppressPetMoveSync: boolean
  suppressChatMoveSync: boolean
  releasePetMoveSyncTimer: ReturnType<typeof setTimeout> | null
  releaseChatMoveSyncTimer: ReturnType<typeof setTimeout> | null
  petFollowTarget: WindowPoint | null
  petFollowTimer: ReturnType<typeof setTimeout> | null
  petManualControlUntil: number
  petPointerActive: boolean
  petRoamTimer: ReturnType<typeof setTimeout> | null
  chatTransition: ChatTransition
  chatLogicalVisible: boolean
}

export function createAssistantWindowState(): AssistantWindowState {
  return {
    petWindow: null,
    chatWindow: null,
    currentPetState: 'idle',
    suppressPetMoveSync: false,
    suppressChatMoveSync: false,
    releasePetMoveSyncTimer: null,
    releaseChatMoveSyncTimer: null,
    petFollowTarget: null,
    petFollowTimer: null,
    petManualControlUntil: 0,
    petPointerActive: false,
    petRoamTimer: null,
    chatTransition: null,
    chatLogicalVisible: false,
  }
}
