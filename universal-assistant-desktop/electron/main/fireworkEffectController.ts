import { screen } from 'electron'
import type { BrowserWindow } from 'electron'
import { createEffectsBrowserWindow } from './windowFactory'
import { clamp } from './windowMotion'

const EFFECT_WIDTH = 440
const EFFECT_HEIGHT = 370
const EFFECT_DURATION_MS = 2400

export function createFireworkEffectController() {
  let window: BrowserWindow | null = null
  let ready = false
  let hideTimer: ReturnType<typeof setTimeout> | null = null
  let pending: { x: number; y: number; burstX: number; burstY: number } | null = null

  function showPending() {
    if (!ready || !pending || !window || window.isDestroyed()) return

    const payload = pending
    pending = null
    window.setBounds({ x: payload.x, y: payload.y, width: EFFECT_WIDTH, height: EFFECT_HEIGHT })
    window.showInactive()
    window.webContents.send('assistant:firework', {
      startX: clamp(payload.burstX - payload.x - 58, 24, EFFECT_WIDTH - 24),
      startY: clamp(payload.burstY - payload.y + 156, 140, EFFECT_HEIGHT - 28),
      burstX: clamp(payload.burstX - payload.x, 120, EFFECT_WIDTH - 120),
      burstY: clamp(payload.burstY - payload.y, 135, EFFECT_HEIGHT - 155),
    })

    if (hideTimer) clearTimeout(hideTimer)
    hideTimer = setTimeout(() => {
      window?.hide()
      hideTimer = null
    }, EFFECT_DURATION_MS)
  }

  function launch(petWindow: BrowserWindow | null) {
    if (!petWindow || petWindow.isDestroyed()) return

    const pet = petWindow.getBounds()
    const workArea = screen.getDisplayMatching(pet).workArea
    const burstX = pet.x + pet.width / 2 + 18
    const burstY = pet.y + 28
    pending = {
      x: Math.round(clamp(burstX - EFFECT_WIDTH / 2, workArea.x, workArea.x + workArea.width - EFFECT_WIDTH)),
      y: Math.round(clamp(burstY - 110, workArea.y, workArea.y + workArea.height - EFFECT_HEIGHT)),
      burstX,
      burstY,
    }

    if (!window || window.isDestroyed()) {
      ready = false
      window = createEffectsBrowserWindow(() => {
        ready = true
        showPending()
      })
      window.on('closed', () => {
        window = null
        ready = false
      })
      return
    }
    showPending()
  }

  function dispose() {
    if (hideTimer) clearTimeout(hideTimer)
    hideTimer = null
    pending = null
    window?.close()
    window = null
  }

  return { launch, dispose }
}
