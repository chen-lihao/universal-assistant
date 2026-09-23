import { Menu } from 'electron'
import type { BrowserWindow } from 'electron'
import type { PetAction } from './types'

export function showPetInteractionMenu(options: {
  petWindow: BrowserWindow
  runAction: (action: PetAction) => void
  onClose: () => void
}) {
  const menu = Menu.buildFromTemplate([
    { label: '打招呼 Wave', click: () => options.runAction('wave') },
    { label: '开心跳 Jump', click: () => options.runAction('jump') },
    { label: '放烟花 Fireworks', click: () => options.runAction('fireworks') },
    { label: '小跑一下 Run', click: () => options.runAction('run') },
    { type: 'separator' },
    { label: '睡觉 Sleep', click: () => options.runAction('sleep') },
    { label: '恢复空闲 Idle', click: () => options.runAction('idle') },
  ])

  menu.popup({
    window: options.petWindow,
    callback: options.onClose,
  })
}
