import { BrowserWindow, Menu } from 'electron'
import type { ContextMenuParams, MenuItemConstructorOptions, WebContents } from 'electron'

type EditAction = 'undo' | 'redo' | 'cut' | 'copy' | 'paste' | 'selectAll'

function runEditAction(action: EditAction, webContents?: WebContents) {
  const target = webContents ?? BrowserWindow.getFocusedWindow()?.webContents
  if (!target || target.isDestroyed()) {
    return
  }

  switch (action) {
    case 'undo':
      target.undo()
      break
    case 'redo':
      target.redo()
      break
    case 'cut':
      target.cut()
      break
    case 'copy':
      target.copy()
      break
    case 'paste':
      target.paste()
      break
    case 'selectAll':
      target.selectAll()
      break
  }
}

function createEditMenuItem(
  label: string,
  action: EditAction,
  accelerator: string,
  enabled: boolean,
  webContents?: WebContents,
): MenuItemConstructorOptions {
  return {
    label,
    accelerator,
    enabled,
    click: () => runEditAction(action, webContents),
  }
}

function createEditMenuItems(params?: ContextMenuParams, webContents?: WebContents): MenuItemConstructorOptions[] {
  const editable = params?.isEditable ?? true
  const hasSelection = Boolean(params?.selectionText)
  const editFlags = params?.editFlags

  if (!editable) {
    return [
      createEditMenuItem('复制 Copy', 'copy', 'CmdOrCtrl+C', hasSelection, webContents),
      { type: 'separator' },
      createEditMenuItem('全选 Select All', 'selectAll', 'CmdOrCtrl+A', true, webContents),
    ]
  }

  return [
    createEditMenuItem('撤销 Undo', 'undo', 'CmdOrCtrl+Z', editFlags ? editFlags.canUndo : true, webContents),
    createEditMenuItem('重做 Redo', 'redo', 'Shift+CmdOrCtrl+Z', editFlags ? editFlags.canRedo : true, webContents),
    { type: 'separator' },
    createEditMenuItem('剪切 Cut', 'cut', 'CmdOrCtrl+X', editFlags ? editFlags.canCut : true, webContents),
    createEditMenuItem('复制 Copy', 'copy', 'CmdOrCtrl+C', editFlags ? editFlags.canCopy : true, webContents),
    createEditMenuItem('粘贴 Paste', 'paste', 'CmdOrCtrl+V', editFlags ? editFlags.canPaste : true, webContents),
    { type: 'separator' },
    createEditMenuItem('全选 Select All', 'selectAll', 'CmdOrCtrl+A', true, webContents),
  ]
}

export function registerApplicationMenu() {
  const template: MenuItemConstructorOptions[] = [
    {
      label: '编辑 Edit',
      submenu: createEditMenuItems(),
    },
  ]

  if (process.platform === 'darwin') {
    template.unshift({ role: 'appMenu' })
  }

  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

export function registerEditorContextMenu(window: BrowserWindow) {
  window.webContents.on('context-menu', (_event, params) => {
    const items = createEditMenuItems(params, window.webContents)
    if (items.length === 0) {
      return
    }

    Menu.buildFromTemplate(items).popup({ window })
  })
}
