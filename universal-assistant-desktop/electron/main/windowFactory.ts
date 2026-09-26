import { BrowserWindow, screen, shell } from 'electron'
import type { Input, Rectangle } from 'electron'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import {
  CHAT_WINDOW_HEIGHT,
  CHAT_WINDOW_MIN_HEIGHT,
  CHAT_WINDOW_MIN_WIDTH,
  CHAT_WINDOW_WIDTH,
  PET_WINDOW_HEIGHT,
  PET_WINDOW_WIDTH,
  RENDERER_DIST,
  VITE_DEV_SERVER_URL,
} from './constants'
import { registerEditorContextMenu } from './editMenu'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

function createWindowOptions() {
  return {
    webPreferences: {
      preload: path.join(__dirname, 'preload.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  }
}

function rendererUrl(view: 'pet' | 'chat' | 'effects') {
  if (VITE_DEV_SERVER_URL) {
    return `${VITE_DEV_SERVER_URL}?view=${view}`
  }

  return {
    file: path.join(RENDERER_DIST, 'index.html'),
    query: { view },
  }
}

function loadRenderer(window: BrowserWindow, view: 'pet' | 'chat' | 'effects') {
  const target = rendererUrl(view)

  if (typeof target === 'string') {
    void window.loadURL(target)
    return
  }

  void window.loadFile(target.file, { query: target.query })
}

function isRendererDevUrl(url: string) {
  if (!VITE_DEV_SERVER_URL) {
    return false
  }

  try {
    return new URL(url).origin === new URL(VITE_DEV_SERVER_URL).origin
  } catch {
    return false
  }
}

function openExternalUrl(url: string) {
  if (!/^https?:\/\//i.test(url) || isRendererDevUrl(url)) {
    return false
  }

  void shell.openExternal(url)
  return true
}

function registerExternalLinkHandling(window: BrowserWindow) {
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (openExternalUrl(url)) {
      return { action: 'deny' }
    }

    return { action: 'allow' }
  })

  window.webContents.on('will-navigate', (event, url) => {
    if (openExternalUrl(url)) {
      event.preventDefault()
    }
  })
}

export function createPetBrowserWindow(handlers: {
  onReadyToShow: () => void
  onDidFinishLoad: () => void
  onMove: () => void
  onClosed: (window: BrowserWindow) => void
}) {
  const workArea = screen.getPrimaryDisplay().workArea
  const window = new BrowserWindow({
    width: PET_WINDOW_WIDTH,
    height: PET_WINDOW_HEIGHT,
    x: workArea.x + workArea.width - PET_WINDOW_WIDTH - 48,
    y: workArea.y + workArea.height - PET_WINDOW_HEIGHT - 48,
    frame: false,
    transparent: true,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    focusable: false,
    acceptFirstMouse: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    backgroundColor: '#00000000',
    show: false,
    ...createWindowOptions(),
  })

  window.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  window.setAlwaysOnTop(true, 'floating')
  window.setIgnoreMouseEvents(true, { forward: true })
  window.once('ready-to-show', handlers.onReadyToShow)
  window.webContents.on('did-finish-load', handlers.onDidFinishLoad)
  window.on('move', handlers.onMove)
  window.on('closed', () => handlers.onClosed(window))
  loadRenderer(window, 'pet')
  return window
}

export function createEffectsBrowserWindow(onDidFinishLoad: () => void) {
  const window = new BrowserWindow({
    width: 440,
    height: 370,
    frame: false,
    transparent: true,
    resizable: false,
    focusable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    backgroundColor: '#00000000',
    show: false,
    ...createWindowOptions(),
  })

  window.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  window.setAlwaysOnTop(true, 'floating')
  window.setIgnoreMouseEvents(true, { forward: true })
  window.webContents.once('did-finish-load', onDidFinishLoad)
  loadRenderer(window, 'effects')
  return window
}

export function createChatBrowserWindow(handlers: {
  onShow: () => void
  onHide: () => void
  onBeforeInput: (event: Electron.Event, input: Input) => void
  onMove: () => void
  onResize: () => void
  onClosed: (window: BrowserWindow) => void
}) {
  const window = new BrowserWindow({
    width: CHAT_WINDOW_WIDTH,
    height: CHAT_WINDOW_HEIGHT,
    minWidth: CHAT_WINDOW_MIN_WIDTH,
    minHeight: CHAT_WINDOW_MIN_HEIGHT,
    frame: false,
    transparent: false,
    resizable: true,
    show: false,
    backgroundColor: '#f7f6fb',
    title: 'Universal Assistant',
    ...createWindowOptions(),
  })

  window.setMenuBarVisibility(false)
  registerEditorContextMenu(window)
  registerExternalLinkHandling(window)
  window.on('show', handlers.onShow)
  window.on('hide', handlers.onHide)
  window.webContents.on('before-input-event', handlers.onBeforeInput)
  window.on('move', handlers.onMove)
  window.on('resize', handlers.onResize)
  window.on('closed', () => handlers.onClosed(window))
  loadRenderer(window, 'chat')
  return window
}

export function horizontalDirectionFromWindow(bounds: Rectangle, workArea: Rectangle) {
  return bounds.x + bounds.width / 2 < workArea.x + workArea.width / 2 ? 1 : -1
}
