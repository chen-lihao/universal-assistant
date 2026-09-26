import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatSessionStore } from '../../chat/stores/chatSessionStore'
import { useFileStore } from '../stores/fileStore'
import { useFileActions } from './useFileActions'

describe('useFileActions', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.unstubAllGlobals())

  it('keeps file errors out of chat and conversation state', async () => {
    vi.stubGlobal('window', {
      assistant: { files: { readText: vi.fn().mockRejectedValue(new Error('read failed')) } },
    })
    const fileStore = useFileStore()
    const chatStore = useChatSessionStore()
    fileStore.selectedFilePath = '/tmp/resume.md'
    chatStore.errorText = '历史会话加载失败'

    await useFileActions({ appendAssistantMessage: vi.fn() }).readSelectedFile()

    expect(fileStore.fileErrorText).toBe('read failed')
    expect(chatStore.errorText).toBe('历史会话加载失败')
  })
})
