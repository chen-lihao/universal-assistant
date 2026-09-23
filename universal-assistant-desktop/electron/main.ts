import { app } from 'electron'
import { createAssistantWindowController } from './main/assistantWindowController'
import { registerApplicationMenu } from './main/editMenu'
import { registerFileIpcHandlers } from './main/fileIpc'

const assistantWindows = createAssistantWindowController()

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
    assistantWindows.handleWindowAllClosed()
  }
})

app.on('activate', () => {
  assistantWindows.handleActivate()
})

app.whenReady().then(() => {
  registerApplicationMenu()
  assistantWindows.registerIpcHandlers()
  registerFileIpcHandlers()
  assistantWindows.createPetWindow()
  assistantWindows.createChatWindow()
})
