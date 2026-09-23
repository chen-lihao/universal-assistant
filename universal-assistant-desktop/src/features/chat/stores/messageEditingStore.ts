import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { UiMessage } from '../model/chatTypes'
import { useChatSessionStore } from './chatSessionStore'

export const useMessageEditingStore = defineStore('messageEditing', () => {
  const chatStore = useChatSessionStore()
  const editingMessageId = ref<string | number>()

  const editingMessage = computed(() => chatStore.editingMessage(editingMessageId.value))
  const isEditingDraft = computed(() => Boolean(editingMessage.value))
  const draftPlaceholder = computed(() =>
    isEditingDraft.value ? '编辑问题，Enter 重新发送，Shift + Enter 换行' : '输入问题，Shift + Enter 换行',
  )

  function startEdit(message: UiMessage) {
    if (chatStore.isSending || !chatStore.canEditUserMessage(message)) {
      return false
    }

    editingMessageId.value = message.id
    chatStore.draft = message.content
    return true
  }

  function clearEditing() {
    editingMessageId.value = undefined
  }

  function cancelEdit() {
    clearEditing()
    chatStore.draft = ''
  }

  return {
    editingMessageId,
    editingMessage,
    isEditingDraft,
    draftPlaceholder,
    startEdit,
    clearEditing,
    cancelEdit,
  }
})
