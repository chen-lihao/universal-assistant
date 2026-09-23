import { storeToRefs } from 'pinia'
import { useChatSessionStore } from '../../chat/stores/chatSessionStore'
import { useFileStore } from '../stores/fileStore'

export function useFileActions(options: { appendAssistantMessage: (content: string) => void }) {
  const chatStore = useChatSessionStore()
  const fileStore = useFileStore()
  const { errorText } = storeToRefs(chatStore)
  const { filePanelOpen, selectedFilePath, fileContent, fileDirty, targetFormat, selectedFileName } =
    storeToRefs(fileStore)

  async function selectFile() {
    if (!window.assistant) {
      return
    }

    filePanelOpen.value = true
    errorText.value = ''
    const result = await window.assistant.files.select()
    if (result.canceled || result.paths.length === 0) {
      return
    }

    selectedFilePath.value = result.paths[0]
    fileStore.resetFileContent()
  }

  async function readSelectedFile() {
    if (!window.assistant || !selectedFilePath.value) {
      return
    }

    try {
      filePanelOpen.value = true
      errorText.value = ''
      const result = await window.assistant.files.readText(selectedFilePath.value)
      fileContent.value = result.content
      fileDirty.value = false
      options.appendAssistantMessage(`已读取 ${result.name}，大小 ${Math.round(result.size / 1024)} KB。`)
    } catch (error) {
      filePanelOpen.value = true
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function saveSelectedFile() {
    if (!window.assistant || !selectedFilePath.value) {
      return
    }

    const confirmed = window.confirm(`确认覆盖写入 ${selectedFileName.value}？`)
    if (!confirmed) {
      return
    }

    try {
      filePanelOpen.value = true
      errorText.value = ''
      await window.assistant.files.writeText({
        filePath: selectedFilePath.value,
        content: fileContent.value,
      })
      fileDirty.value = false
      options.appendAssistantMessage(`已保存 ${selectedFileName.value}。`)
    } catch (error) {
      filePanelOpen.value = true
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function saveTextAs() {
    if (!window.assistant || !fileContent.value) {
      return
    }

    try {
      filePanelOpen.value = true
      errorText.value = ''
      const result = await window.assistant.files.saveTextAs({
        defaultPath: selectedFilePath.value || undefined,
        content: fileContent.value,
      })
      if (!result.canceled && result.path) {
        selectedFilePath.value = result.path
        fileDirty.value = false
        options.appendAssistantMessage(`已另存为 ${result.name || result.path}。`)
      }
    } catch (error) {
      filePanelOpen.value = true
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function convertSelectedFile() {
    if (!window.assistant || !selectedFilePath.value) {
      return
    }

    try {
      filePanelOpen.value = true
      errorText.value = ''
      const result = await window.assistant.files.convertText({
        filePath: selectedFilePath.value,
        targetFormat: targetFormat.value,
      })
      fileContent.value = result.content
      fileDirty.value = true
      options.appendAssistantMessage(
        `已转换为 ${result.targetFormat.toUpperCase()} 内容。确认无误后可另存，建议路径：${result.defaultPath}`,
      )
    } catch (error) {
      filePanelOpen.value = true
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  return {
    selectFile,
    readSelectedFile,
    saveSelectedFile,
    saveTextAs,
    convertSelectedFile,
  }
}
