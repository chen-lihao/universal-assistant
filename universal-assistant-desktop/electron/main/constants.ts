import { fileURLToPath } from 'node:url'
import path from 'node:path'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

process.env.APP_ROOT = path.join(__dirname, '..')

export const VITE_DEV_SERVER_URL = process.env['VITE_DEV_SERVER_URL']
export const RENDERER_DIST = path.join(process.env.APP_ROOT, 'dist')

process.env.VITE_PUBLIC = VITE_DEV_SERVER_URL ? path.join(process.env.APP_ROOT, 'public') : RENDERER_DIST

export const BACKEND_URL = process.env['ASSISTANT_BACKEND_URL'] || 'http://localhost:8080'
export const WINDOW_GAP = 14
export const CHAT_WINDOW_WIDTH = 440
export const CHAT_WINDOW_HEIGHT = 640
export const CHAT_WINDOW_MIN_WIDTH = 380
export const CHAT_WINDOW_MIN_HEIGHT = 520
export const PET_WINDOW_WIDTH = 250
export const PET_WINDOW_HEIGHT = 330
export const FOLLOW_STEP_MS = 16
export const FOLLOW_STIFFNESS = 0.2
export const ROAM_MIN_DELAY_MS = 20000
export const ROAM_MAX_DELAY_MS = 30000
export const ROAM_MIN_DURATION_MS = 900
export const ROAM_MAX_DURATION_MS = 1300
export const ROAM_RANGE_X = 36
export const ROAM_RANGE_Y = 82
export const ROAM_MIN_DISTANCE_Y = 42
export const CHAT_TRANSITION_MS = 230
export const CHAT_COLLAPSED_SIZE = 92
